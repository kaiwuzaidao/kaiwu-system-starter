package com.kaiwu.starter.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 使用一次性真实 MySQL 验证业务变更与账本提交在同一事务，并发唯一键跨连接生效。 */
class JdbcAgentWriteLedgerMySqlTest {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
                    DockerImageName.parse(System.getenv().getOrDefault("KAIWU_TEST_MYSQL_IMAGE", "mysql:8.4"))
                            .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("agent_write_test")
            .withUsername("agent_write_test")
            .withPassword("agent_write_test")
            .withStartupTimeout(Duration.ofMinutes(3));
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static JdbcAgentWriteLedger ledger;

    @BeforeAll
    static void start() {
        MYSQL.start();
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                "CREATE TABLE kaiwu_agent_write_receipt (project_id VARCHAR(128) NOT NULL, action_id CHAR(36) NOT NULL, user_id VARCHAR(128) NOT NULL, session_id VARCHAR(128) NOT NULL, capability_code VARCHAR(128) NOT NULL, capability_version VARCHAR(128) NOT NULL, expires_at_millis BIGINT NOT NULL, binding_hash CHAR(64) NOT NULL, result_json TEXT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (project_id, action_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE domain_counter (id INT PRIMARY KEY, value INT NOT NULL) ENGINE=InnoDB");
        ledger = new JdbcAgentWriteLedger(dataSource, new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void stop() {
        MYSQL.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM kaiwu_agent_write_receipt");
        jdbc.update("DELETE FROM domain_counter");
        jdbc.update("INSERT INTO domain_counter (id, value) VALUES (1, 0)");
    }

    @Test
    void sixteenConcurrentCallsCommitOneDomainChangeAndOneReceipt() throws Exception {
        AgentWriteAction action = action(UUID.randomUUID(), "a".repeat(64));
        AtomicInteger entered = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(16)) {
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (int index = 0; index < 16; index++) {
                Callable<String> call = () -> {
                    start.await();
                    return ledger.executeOnce(action, () -> {
                        entered.incrementAndGet();
                        jdbc.update("UPDATE domain_counter SET value = value + 1 WHERE id = 1");
                        return "{\"status\":\"done\"}";
                    });
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            for (var future : futures)
                assertThat(future.get(15, TimeUnit.SECONDS)).isEqualTo("{\"status\":\"done\"}");
        }
        assertThat(entered).hasValue(1);
        assertThat(counter()).isEqualTo(1);
        assertThat(receipts()).isEqualTo(1);
        assertThat(ledger.result(action)).contains("{\"status\":\"done\"}");
        assertThat(jdbc.queryForObject(
                        "SELECT user_id FROM kaiwu_agent_write_receipt WHERE project_id = ? AND action_id = ?",
                        String.class,
                        action.projectId(),
                        action.actionId().toString()))
                .isEqualTo("user-1");
    }

    @Test
    void changedBindingCannotReadOrRepeatPriorAction() {
        UUID id = UUID.randomUUID();
        AgentWriteAction original = action(id, "a".repeat(64));
        AgentWriteAction changed = action(id, "b".repeat(64));
        ledger.executeOnce(original, () -> "{\"status\":\"done\"}");
        assertThatThrownBy(() -> ledger.result(changed)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.executeOnce(changed, () -> {
                    jdbc.update("UPDATE domain_counter SET value = value + 1 WHERE id = 1");
                    return "{\"status\":\"bad\"}";
                }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(counter()).isZero();
        assertThat(receipts()).isEqualTo(1);
    }

    @Test
    void domainFailureAndResultFailureRollBackDomainAndReceiptTogether() {
        AgentWriteAction domainFails = action(UUID.randomUUID(), "a".repeat(64));
        assertThatThrownBy(() -> ledger.executeOnce(domainFails, () -> {
                    jdbc.update("UPDATE domain_counter SET value = value + 1 WHERE id = 1");
                    throw new IllegalStateException("domain rejected");
                }))
                .isInstanceOf(IllegalStateException.class);
        AgentWriteAction resultFails = action(UUID.randomUUID(), "a".repeat(64));
        assertThatThrownBy(() -> ledger.executeOnce(resultFails, () -> {
                    jdbc.update("UPDATE domain_counter SET value = value + 1 WHERE id = 1");
                    return "not json";
                }))
                .isInstanceOf(RuntimeException.class);
        assertThat(counter()).isZero();
        assertThat(receipts()).isZero();
        assertThat(ledger.result(domainFails)).isEmpty();
        assertThat(ledger.result(resultFails)).isEmpty();
    }

    @Test
    void committedResultSurvivesLostResponseAndExpiredActionDoesNotExecute() {
        AgentWriteAction committed = action(UUID.randomUUID(), "a".repeat(64));
        ledger.executeOnce(committed, () -> {
            jdbc.update("UPDATE domain_counter SET value = value + 1 WHERE id = 1");
            return "{\"id\":\"1\"}";
        });
        assertThat(ledger.result(committed)).contains("{\"id\":\"1\"}");
        AgentWriteAction expired = new AgentWriteAction(
                committed.projectId(),
                UUID.randomUUID(),
                committed.userId(),
                committed.sessionId(),
                committed.capabilityCode(),
                committed.version(),
                committed.manifestHash(),
                committed.paramsHash(),
                committed.preconditionsHash(),
                committed.previewHash(),
                System.currentTimeMillis() - 1);
        assertThatThrownBy(() -> ledger.executeOnce(expired, () -> "{\"bad\":true}"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(counter()).isEqualTo(1);
        assertThat(receipts()).isEqualTo(1);
    }

    @Test
    void ledgerRequiresSameTransactionDataSourceAndPreviewHasLimits() {
        var other = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        assertThatThrownBy(() -> new JdbcAgentWriteLedger(dataSource, new DataSourceTransactionManager(other)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentWritePreview(Map.of("target", "order"), 101, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new AgentWritePreview(Map.of("target", "order"), 1, "{\"version\":4}").displayFields())
                .containsEntry("target", "order");
    }

    private static AgentWriteAction action(UUID id, String paramsHash) {
        return new AgentWriteAction(
                "1788074432190000001",
                id,
                "user-1",
                "session-1",
                "crm.note.update",
                "1",
                "a".repeat(64),
                paramsHash,
                "b".repeat(64),
                "c".repeat(64),
                System.currentTimeMillis() + 300_000);
    }

    private static int counter() {
        return jdbc.queryForObject("SELECT value FROM domain_counter WHERE id = 1", Integer.class);
    }

    private static int receipts() {
        return jdbc.queryForObject("SELECT count(*) FROM kaiwu_agent_write_receipt", Integer.class);
    }
}
