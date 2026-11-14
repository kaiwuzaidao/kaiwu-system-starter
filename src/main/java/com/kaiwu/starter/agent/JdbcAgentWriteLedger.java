package com.kaiwu.starter.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MySQL 事务账本参考实现。业务 Handler 必须使用同一 DataSource/事务管理器，且不能另开事务。
 * 此类不自动装配，也不创建表；只应由未来的固定、已验签写入口调用。
 */
public final class JdbcAgentWriteLedger implements AgentWriteLedger {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcAgentWriteLedger(DataSource dataSource, DataSourceTransactionManager manager) {
        Objects.requireNonNull(dataSource, "dataSource");
        if (manager == null || manager.getDataSource() != dataSource)
            throw new IllegalArgumentException("Agent ledger must share the business transaction DataSource");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setTimeout(10);
    }

    @Override
    public String executeOnce(AgentWriteAction action, Supplier<String> domainMutation) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(domainMutation, "domainMutation");
        if (action.expiresAtMillis() <= System.currentTimeMillis())
            return result(action).orElseThrow(() -> new IllegalStateException("Agent write action expired"));
        try {
            return transaction.execute(status -> {
                try {
                    jdbc.update(
                            "INSERT INTO kaiwu_agent_write_receipt (project_id, action_id, user_id, session_id, capability_code, capability_version, expires_at_millis, binding_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                            action.projectId(),
                            action.actionId().toString(),
                            action.userId(),
                            action.sessionId(),
                            action.capabilityCode(),
                            action.version(),
                            action.expiresAtMillis(),
                            action.bindingHash());
                } catch (DuplicateKeyException duplicate) {
                    throw new ExistingAction(duplicate);
                }
                String response = domainMutation.get();
                if (response == null || response.getBytes(StandardCharsets.UTF_8).length > 16_384)
                    throw new IllegalArgumentException("Invalid Agent write result");
                AgentExecutionCodec.parameters(response);
                if (jdbc.update(
                                "UPDATE kaiwu_agent_write_receipt SET result_json = ? WHERE project_id = ? AND action_id = ? AND binding_hash = ? AND result_json IS NULL",
                                response,
                                action.projectId(),
                                action.actionId().toString(),
                                action.bindingHash())
                        != 1) throw new IllegalStateException("Agent write receipt unavailable");
                return response;
            });
        } catch (ExistingAction duplicate) {
            // 对手事务提交前唯一键插入会等待；只读已提交结果，绝不再调用领域写入。
            return result(action).orElseThrow(() -> new IllegalStateException("Agent write outcome unknown"));
        }
    }

    @Override
    public Optional<String> result(AgentWriteAction action) {
        Objects.requireNonNull(action, "action");
        List<Receipt> rows = jdbc.query(
                "SELECT binding_hash, result_json FROM kaiwu_agent_write_receipt WHERE project_id = ? AND action_id = ?",
                (result, index) -> new Receipt(result.getString(1), result.getString(2)),
                action.projectId(),
                action.actionId().toString());
        if (rows.isEmpty()) return Optional.empty();
        Receipt receipt = rows.getFirst();
        if (!MessageDigest.isEqual(
                receipt.bindingHash().getBytes(StandardCharsets.US_ASCII),
                action.bindingHash().getBytes(StandardCharsets.US_ASCII)))
            throw new IllegalArgumentException("Agent write action binding mismatch");
        if (receipt.resultJson() == null) throw new IllegalStateException("Agent write outcome unknown");
        return Optional.of(receipt.resultJson());
    }

    private record Receipt(String bindingHash, String resultJson) {}

    private static final class ExistingAction extends RuntimeException {
        private ExistingAction(DuplicateKeyException duplicate) {
            super("Agent write action already exists", duplicate);
        }
    }
}
