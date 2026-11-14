package com.kaiwu.starter.storage.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.kaiwu.starter.storage.adapter.LocalDiskObjectStorage;
import com.kaiwu.starter.storage.exception.ObjectNotFoundException;
import com.kaiwu.starter.storage.exception.ObjectStorageException;
import com.kaiwu.starter.storage.model.StoredFile;
import com.kaiwu.starter.storage.model.StoredFileQuery;
import com.kaiwu.starter.storage.model.StoredFileStatus;
import com.kaiwu.starter.storage.repository.JdbcStoredFileRepository;
import com.kaiwu.starter.storage.service.ObjectKeyPolicy;
import com.kaiwu.starter.storage.service.StoredFileService;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 用真实 MySQL 验证 docs/storage-file.sql 的建表语句与 JDBC 仓库的查询、状态迁移和 LIKE 转义。 */
class JdbcStoredFileRepositoryMySqlTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
                    DockerImageName.parse(System.getenv().getOrDefault("KAIWU_TEST_MYSQL_IMAGE", "mysql:8.4"))
                            .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("file_test")
            .withUsername("file_test")
            .withPassword("file_test")
            .withStartupTimeout(Duration.ofMinutes(3));

    private static JdbcTemplate jdbc;
    private static JdbcStoredFileRepository repository;

    /**
     * 启动容器并按文档里的 DDL 建表。
     *
     * @throws Exception 读取 DDL 失败时抛出
     */
    @BeforeAll
    static void start() throws Exception {
        MYSQL.start();
        jdbc = new JdbcTemplate(
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.execute(Files.readString(Path.of("docs/storage-file.sql")));
        jdbc.execute(Files.readString(Path.of("docs/storage-file-retention.sql")));
        repository = new JdbcStoredFileRepository(jdbc);
    }

    /** 关闭容器。 */
    @AfterAll
    static void stop() {
        MYSQL.stop();
    }

    /** 每个用例从空表开始。 */
    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM kaiwu_file_reference");
        jdbc.update("DELETE FROM kaiwu_file");
    }

    /**
     * 构造一条记录。
     *
     * @param id 文件 ID
     * @param name 原始文件名
     * @param status 状态
     * @param createdAt 创建时间
     * @return 记录
     */
    private static StoredFile file(String id, String name, StoredFileStatus status, LocalDateTime createdAt) {
        return new StoredFile(
                id,
                "local",
                "demo/invoice/2026/10/" + id + ".pdf",
                name,
                "application/pdf",
                10,
                null,
                "invoice",
                null,
                status,
                "7",
                createdAt,
                status == StoredFileStatus.PENDING ? null : createdAt,
                null);
    }

    /** 引用必须跨仓库实例持久化，并阻止软删和后续清理。 */
    @Test
    void retentionSurvivesRepositoryRestartAndRejectsDeletion() {
        var protectedRepository = new JdbcStoredFileRepository(jdbc, true);
        LocalDateTime now = LocalDateTime.now();
        repository.insert(file("retained", "proof.pdf", StoredFileStatus.ACTIVE, now));
        assertThat(protectedRepository.retain("retained", "workflow", "generation-1"))
                .isTrue();
        assertThat(protectedRepository.retain("retained", "workflow", "generation-1"))
                .isTrue();
        var restarted = new JdbcStoredFileRepository(jdbc, true);
        assertThat(restarted.markDeleted("retained", now)).isFalse();
        assertThat(restarted.claimPurge(repository.find("retained").orElseThrow()))
                .isFalse();
        assertThat(restarted.find("retained").orElseThrow().status()).isEqualTo(StoredFileStatus.ACTIVE);
    }

    /** 并发引用与软删除只能有一方生效，不能产生被删除的保留证据。 */
    @Test
    void retainAndDeleteHaveExactlyOneWinner() throws Exception {
        var protectedRepository = new JdbcStoredFileRepository(jdbc, true);
        LocalDateTime now = LocalDateTime.now();
        repository.insert(file("race", "proof.pdf", StoredFileStatus.ACTIVE, now));
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var executor = new SimpleAsyncTaskExecutor("retention-race-")) {
            executor.setVirtualThreads(true);
            var retained = executor.submit(() -> {
                ready.countDown();
                go.await();
                return protectedRepository.retain("race", "workflow", "generation-1");
            });
            var deleted = executor.submit(() -> {
                ready.countDown();
                go.await();
                return protectedRepository.markDeleted("race", now);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(retained.get(10, TimeUnit.SECONDS)).isNotEqualTo(deleted.get(10, TimeUnit.SECONDS));
        } finally {
            go.countDown();
        }
    }

    /** 清理先取得独占状态，迟到确认/引用不能复活对象，失败可再次领取。 */
    @Test
    void purgeClaimPreventsLateConfirmationAndCanRetry() {
        var protectedRepository = new JdbcStoredFileRepository(jdbc, true);
        LocalDateTime now = LocalDateTime.now();
        repository.insert(file("pending", "proof.pdf", StoredFileStatus.PENDING, now.minusDays(2)));
        var pending = repository.find("pending").orElseThrow();
        assertThat(protectedRepository.claimPurge(pending)).isTrue();
        assertThat(protectedRepository.activate("pending", 10, "application/pdf", now))
                .isFalse();
        assertThat(protectedRepository.retain("pending", "workflow", "generation-1"))
                .isFalse();
        assertThat(protectedRepository.findExpired(StoredFileStatus.DELETED, now, 10))
                .hasSize(1);
        assertThat(protectedRepository.claimPurge(pending)).isTrue();
        protectedRepository.purge("pending");
        assertThat(protectedRepository.find("pending")).isEmpty();
    }

    /** 后台预留身份在网络前落库，重试只读原对象且引用防删除。 */
    @Test
    void evidenceUploadIsDurableImmutableAndRetained() throws Exception {
        var protectedRepository = new JdbcStoredFileRepository(jdbc, true);
        var directory = Files.createTempDirectory("starter-evidence-");
        var clock = Clock.systemUTC();
        var policy = new ObjectKeyPolicy("demo", clock);
        var storage = new LocalDiskObjectStorage(directory, policy, 1024);
        var service = new StoredFileService(
                protectedRepository, storage, ref -> storage, policy, clock, 1024, Duration.ofMinutes(5));
        String id = UUID.randomUUID().toString();
        String digest =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[] {1, 2, 3}));
        try {
            var pending = service.reserveEvidence(id, "workflow", "generation-1", 3, digest);
            assertThat(pending.originalName()).isEqualTo("evidence.bin");
            assertThat(pending.contentType()).isEqualTo("application/octet-stream");
            assertThat(repository.find(id)).isPresent();
            assertThat(storage.stat(pending.objectKey())).isEmpty();
            var file = service.uploadEvidence(id, new ByteArrayInputStream(new byte[] {1, 2, 3}));
            assertThat(file.status()).isEqualTo(StoredFileStatus.ACTIVE);
            assertThat(service.uploadEvidence(id, new ByteArrayInputStream(new byte[] {9, 9, 9}))
                            .id())
                    .isEqualTo(id);
            try (var stream = service.open(file)) {
                assertThat(stream.readAllBytes()).containsExactly((byte) 1, (byte) 2, (byte) 3);
            }
            assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ObjectNotFoundException.class);
        } finally {
            try (var files = Files.walk(directory)) {
                for (var file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    /** 只有正确所属服务/引用可解除保留，解除后才允许正常删除清理。 */
    @Test
    void exactReleaseAllowsConfirmedUnreferencedCleanup() {
        var safe = new JdbcStoredFileRepository(jdbc, true);
        var now = LocalDateTime.now();
        repository.insert(file("release", "proof.pdf", StoredFileStatus.ACTIVE, now));
        assertThat(safe.retain("release", "workflow", "old-attempt")).isTrue();
        assertThat(safe.release("release", "forged", "old-attempt")).isFalse();
        assertThat(safe.markDeleted("release", now)).isFalse();
        assertThat(safe.release("release", "workflow", "old-attempt")).isTrue();
        assertThat(safe.markDeleted("release", now)).isTrue();
        assertThat(safe.claimPurge(safe.find("release").orElseThrow())).isTrue();
        safe.purge("release");
        assertThat(safe.find("release")).isEmpty();
    }

    /** 大小写不同的服务或引用不能释放他人的保留，两个合法引用也不能被不区分大小写的索引折叠。 */
    @Test
    void releaseUsesExactCaseSensitiveReferenceIdentity() {
        var safe = new JdbcStoredFileRepository(jdbc, true);
        var now = LocalDateTime.now();
        repository.insert(file("case-sensitive", "proof.pdf", StoredFileStatus.ACTIVE, now));
        assertThat(safe.retain("case-sensitive", "workflow", "Attempt-A")).isTrue();
        assertThat(safe.release("case-sensitive", "WORKFLOW", "Attempt-A")).isFalse();
        assertThat(safe.release("case-sensitive", "workflow", "attempt-a")).isFalse();
        assertThat(safe.retain("case-sensitive", "workflow", "attempt-a")).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM kaiwu_file_reference WHERE file_id='case-sensitive'", Long.class))
                .isEqualTo(2);
        assertThat(safe.release("case-sensitive", "workflow", "Attempt-A")).isTrue();
        assertThat(safe.markDeleted("case-sensitive", now)).isFalse();
        assertThat(safe.release("case-sensitive", "workflow", "attempt-a")).isTrue();
        assertThat(safe.markDeleted("case-sensitive", now)).isTrue();
    }

    /** 预发布旧表仍为不区分大小写索引时，别名冲突必须拒绝，不能假报精确保留成功。 */
    @Test
    void rejectsAliasCollisionInAnExistingCaseInsensitiveReferenceTable() {
        jdbc.execute(
                "ALTER TABLE kaiwu_file_reference MODIFY owner_service VARCHAR(64) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL, MODIFY reference_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL");
        try {
            var safe = new JdbcStoredFileRepository(jdbc, true);
            repository.insert(file("legacy-collation", "proof.pdf", StoredFileStatus.ACTIVE, LocalDateTime.now()));
            assertThat(safe.retain("legacy-collation", "workflow", "Attempt-A")).isTrue();
            assertThat(safe.retain("legacy-collation", "WORKFLOW", "attempt-a")).isFalse();
            assertThat(safe.release("legacy-collation", "WORKFLOW", "attempt-a"))
                    .isFalse();
            assertThat(safe.markDeleted("legacy-collation", LocalDateTime.now()))
                    .isFalse();
        } finally {
            jdbc.execute(
                    "ALTER TABLE kaiwu_file_reference MODIFY owner_service VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, MODIFY reference_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL");
        }
    }

    /** 调用方已有RR快照时仍必须读取已提交的最新引用，不能让快照隐藏并发提交的保留。 */
    @Test
    void deletionChecksCurrentReferencesInsideAnExistingSnapshot() {
        var safe = new JdbcStoredFileRepository(jdbc, true);
        var now = LocalDateTime.now();
        repository.insert(file("snapshot", "proof.pdf", StoredFileStatus.ACTIVE, now));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kaiwu_file_reference", Long.class))
                    .isZero();
            boolean retained = CompletableFuture.supplyAsync(() -> new JdbcStoredFileRepository(jdbc, true)
                            .retain("snapshot", "workflow", "committed-reference"))
                    .join();
            assertThat(retained).isTrue();
            assertThat(safe.markDeleted("snapshot", now)).isFalse();
        });
        assertThat(safe.find("snapshot").orElseThrow().status()).isEqualTo(StoredFileStatus.ACTIVE);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM kaiwu_file_reference WHERE file_id='snapshot'", Long.class))
                .isEqualTo(1);
    }

    /** 错误正文不会被重试覆盖；UPLOADING保留身份，普通清理不删除结果不确定的证据。 */
    @Test
    void failedIntegrityNeverOverwritesOrAutomaticallyPurgesEvidence() throws Exception {
        var directory = Files.createTempDirectory("retention-integrity-");
        try {
            var safe = new JdbcStoredFileRepository(jdbc, true);
            var clock = Clock.systemUTC();
            var policy = new ObjectKeyPolicy("demo", clock);
            var storage = new LocalDiskObjectStorage(directory, policy, 1024);
            var service =
                    new StoredFileService(safe, storage, ref -> storage, policy, clock, 1024, Duration.ofMinutes(5));
            String id = UUID.randomUUID().toString();
            String digest = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[] {1, 2, 3}));
            var reservation = service.reserveEvidence(id, "workflow", "attempt", 3, digest);
            assertThatThrownBy(() -> service.uploadEvidence(id, new ByteArrayInputStream(new byte[] {9, 9, 9})))
                    .isInstanceOf(ObjectStorageException.class);
            assertThatThrownBy(() -> service.uploadEvidence(id, new ByteArrayInputStream(new byte[] {1, 2, 3})))
                    .isInstanceOf(ObjectStorageException.class);
            assertThat(safe.find(id).orElseThrow().status()).isEqualTo(StoredFileStatus.UPLOADING);
            assertThat(service.purgeExpired(Duration.ZERO, Duration.ZERO)).isZero();
            try (var input = storage.get(reservation.objectKey())) {
                assertThat(input.readAllBytes()).containsExactly((byte) 9, (byte) 9, (byte) 9);
            }
        } finally {
            removeTree(directory);
        }
    }

    /** 对象已写入但回执丢失时只读核验，随后同身份激活且不会重复PUT。 */
    @Test
    void recoversLostObjectReceiptWithoutRepeatingTheWrite() throws Exception {
        var directory = Files.createTempDirectory("retention-receipt-");
        try {
            var safe = new JdbcStoredFileRepository(jdbc, true);
            var clock = Clock.systemUTC();
            var policy = new ObjectKeyPolicy("demo", clock);
            var storage = spy(new LocalDiskObjectStorage(directory, policy, 1024));
            doAnswer(call -> {
                        call.callRealMethod();
                        throw new ObjectStorageException("test-only lost PUT receipt");
                    })
                    .when(storage)
                    .put(anyString(), any(), anyLong(), anyString());
            var service =
                    new StoredFileService(safe, storage, ref -> storage, policy, clock, 1024, Duration.ofMinutes(5));
            String id = UUID.randomUUID().toString();
            String digest = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[] {1, 2, 3}));
            service.reserveEvidence(id, "workflow", "attempt", 3, digest);
            assertThatThrownBy(() -> service.uploadEvidence(id, new ByteArrayInputStream(new byte[] {1, 2, 3})))
                    .isInstanceOf(ObjectStorageException.class);
            assertThat(service.uploadEvidence(id, new ByteArrayInputStream(new byte[] {9, 9, 9}))
                            .status())
                    .isEqualTo(StoredFileStatus.ACTIVE);
            verify(storage, times(1)).put(anyString(), any(), anyLong(), anyString());
            assertThat(safe.markDeleted(id, LocalDateTime.now())).isFalse();
        } finally {
            removeTree(directory);
        }
    }

    /** 预留INSERT实际提交后丢回执，固定ID重试必须找回原key且网络前已有持久身份。 */
    @Test
    void reservationLostReceiptKeepsTheSameDurableIdentity() throws Exception {
        var directory = Files.createTempDirectory("retention-reserve-");
        try {
            var safe = spy(new JdbcStoredFileRepository(jdbc, true));
            doAnswer(call -> {
                        call.callRealMethod();
                        throw new IllegalStateException("test-only lost reservation receipt");
                    })
                    .when(safe)
                    .insert(any());
            var clock = Clock.systemUTC();
            var policy = new ObjectKeyPolicy("demo", clock);
            var storage = new LocalDiskObjectStorage(directory, policy, 1024);
            var service =
                    new StoredFileService(safe, storage, ref -> storage, policy, clock, 1024, Duration.ofMinutes(5));
            String id = UUID.randomUUID().toString();
            String digest = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[] {1, 2, 3}));
            assertThatThrownBy(() -> service.reserveEvidence(id, "workflow", "attempt", 3, digest))
                    .isInstanceOf(IllegalStateException.class);
            var original = safe.find(id).orElseThrow();
            assertThat(service.reserveEvidence(id, "workflow", "attempt", 3, digest)
                            .objectKey())
                    .isEqualTo(original.objectKey());
            assertThat(storage.stat(original.objectKey())).isEmpty();
            assertThatThrownBy(() -> service.reserveEvidence(id, "workflow", "other-attempt", 3, digest))
                    .isInstanceOf(ObjectStorageException.class);
        } finally {
            removeTree(directory);
        }
    }

    /** 保留与调用方业务事务一起提交或回滚；证据网络IO禁止在该事务内发生。 */
    @Test
    void retentionJoinsCallerTransactionAndEvidenceIoStaysOutsideIt() throws Exception {
        var directory = Files.createTempDirectory("retention-transaction-");
        try {
            var safe = new JdbcStoredFileRepository(jdbc, true);
            var now = LocalDateTime.now();
            repository.insert(file("rollback", "proof.pdf", StoredFileStatus.ACTIVE, now));
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE kaiwu_file SET biz_id='business-write' WHERE id='rollback'");
                assertThat(safe.retain("rollback", "workflow", "transaction-reference"))
                        .isTrue();
                status.setRollbackOnly();
            });
            assertThat(safe.find("rollback").orElseThrow().bizId()).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kaiwu_file_reference", Long.class))
                    .isZero();
            var clock = Clock.systemUTC();
            var policy = new ObjectKeyPolicy("demo", clock);
            var storage = new LocalDiskObjectStorage(directory, policy, 1024);
            var service =
                    new StoredFileService(safe, storage, ref -> storage, policy, clock, 1024, Duration.ofMinutes(5));
            String id = UUID.randomUUID().toString();
            var reserved = service.reserveEvidence(id, "workflow", "attempt", 3, "a".repeat(64));
            transaction.executeWithoutResult(status -> assertThatThrownBy(
                            () -> service.uploadEvidence(id, new ByteArrayInputStream(new byte[] {1, 2, 3})))
                    .isInstanceOf(IllegalStateException.class));
            assertThat(safe.find(id).orElseThrow().status()).isEqualTo(StoredFileStatus.RESERVED);
            assertThat(storage.stat(reserved.objectKey())).isEmpty();
        } finally {
            removeTree(directory);
        }
    }

    /** 只删除当前测试创建的临时存储目录，结果不确定对象的保留规则仍由用例单独断言。 */
    private static void removeTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** 往返读写、状态迁移只在合法前置状态下生效。 */
    @Test
    void roundTripAndStateTransitions() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 29, 8, 0);
        repository.insert(file("a", "a.pdf", StoredFileStatus.PENDING, now));

        assertThat(repository.activate("a", 12, "application/pdf", now.plusMinutes(1)))
                .isTrue();
        assertThat(repository.activate("a", 12, "application/pdf", now.plusMinutes(2)))
                .isFalse();
        assertThat(repository.find("a").orElseThrow().sizeBytes()).isEqualTo(12);
        assertThat(repository.bind("a", "R-1")).isTrue();
        assertThat(repository.markDeleted("a", now.plusHours(1))).isTrue();
        assertThat(repository.markDeleted("a", now.plusHours(2))).isFalse();
        assertThat(repository.bind("a", "R-2")).isFalse();
        assertThat(repository.find("a").orElseThrow().status()).isEqualTo(StoredFileStatus.DELETED);
    }

    /** 分页只含生效记录，关键字中的 % 与 _ 按字面匹配，不会变成通配符。 */
    @Test
    void pageFiltersActiveAndEscapesLikeWildcards() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 29, 8, 0);
        repository.insert(file("a", "100%_发票.pdf", StoredFileStatus.ACTIVE, now));
        repository.insert(file("b", "1000发票.pdf", StoredFileStatus.ACTIVE, now.plusMinutes(1)));
        repository.insert(file("c", "待确认.pdf", StoredFileStatus.PENDING, now.plusMinutes(2)));

        assertThat(repository
                        .page(new StoredFileQuery(null, null, null, null), 1, 20)
                        .records())
                .extracting(StoredFile::id)
                .containsExactly("b", "a");
        assertThat(repository
                        .page(new StoredFileQuery("%_", null, null, null), 1, 20)
                        .records())
                .extracting(StoredFile::id)
                .containsExactly("a");
        assertThat(repository
                        .page(new StoredFileQuery(null, null, null, null), 1, 1)
                        .total())
                .isEqualTo(2);
    }

    /** 过期查询：待确认按创建时间、已删除按删除时间；物理删除后不再出现。 */
    @Test
    void findExpiredAndPurge() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 29, 8, 0);
        repository.insert(file("p", "p.pdf", StoredFileStatus.PENDING, now.minusHours(30)));
        repository.insert(file("q", "q.pdf", StoredFileStatus.PENDING, now));
        repository.insert(file("d", "d.pdf", StoredFileStatus.ACTIVE, now.minusDays(9)));
        repository.markDeleted("d", now.minusDays(8));

        assertThat(repository.findExpired(StoredFileStatus.PENDING, now.minusHours(24), 10))
                .extracting(StoredFile::id)
                .containsExactly("p");
        assertThat(repository.findExpired(StoredFileStatus.DELETED, now.minusDays(7), 10))
                .extracting(StoredFile::id)
                .containsExactly("d");

        repository.purge("p");
        assertThat(repository.find("p")).isEmpty();
    }
}
