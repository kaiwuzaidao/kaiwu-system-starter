package com.kaiwu.datascope;

import com.baomidou.mybatisplus.extension.plugins.handler.MultiDataPermissionHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.kaiwu.starter.KaiwuContext;
import com.kaiwu.starter.auth.model.ContextDataScope;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

/**
 * 把数据范围谓词改写进 SQL 的核心：借用 MyBatis-Plus 的多表数据权限遍历（子查询、JOIN、UNION、WITH），
 * 谓词由本类生成。
 *
 * <p>谓词里的值一律用占位符，不拼接字面量；占位符以 {@code ?1000000+k} 形式暂存，由调用方在改写后换回
 * 普通 {@code ?} 并补上参数映射，见 {@link Rewritten}。</p>
 */
final class ScopeRewriter extends DataPermissionInterceptor {

    /** 占位符序号起点，远大于任何真实 SQL 里出现的位置参数序号。 */
    static final int PLACEHOLDER_BASE = 1_000_000;

    private static final Pattern REPLACE_INTO = Pattern.compile("(?is)\\breplace\\s+into\\b");
    private static final Pattern ON_DUPLICATE = Pattern.compile("(?is)\\bon\\s+duplicate\\s+key\\b");

    /**
     * 一次改写的结果。
     *
     * @param sql    含 {@code ?1000000+k} 占位符的新 SQL
     * @param values 占位符按 k 排列的取值
     */
    record Rewritten(String sql, List<Long> values) {}

    private final DataScopeRegistry registry;
    private final Pattern tableMention;
    private final ThreadLocal<List<Long>> values = new ThreadLocal<>();

    /** 以清单创建改写器，并让 MyBatis-Plus 的遍历回调本类的谓词生成。 */
    ScopeRewriter(DataScopeRegistry registry) {
        this.registry = registry;
        String names = String.join(
                "|", registry.resources().stream().map(DataScopeResource::table).toList());
        this.tableMention = names.isEmpty()
                ? null
                : Pattern.compile("(?<![a-z0-9_])(?:" + names + ")(?![a-z0-9_])", Pattern.CASE_INSENSITIVE);
        setDataPermissionHandler((MultiDataPermissionHandler) this::predicate);
    }

    /**
     * 改写一条语句。
     *
     * @param sql  MyBatis 生成的 SQL
     * @param msId MappedStatement id，仅用于诊断
     * @return 需要改写时返回结果；与受控表无关或无需改写时为 {@code null}
     * @throws DataScopeViolationException 无法解析、禁止的语句形态，或没有 Context
     */
    Rewritten rewrite(String sql, String msId) {
        if (tableMention == null || !tableMention.matcher(sql).find()) {
            return null;
        }
        DataScopes.Frame frame = DataScopes.frame();
        boolean unrestricted = frame != null && frame.mode() == DataScopes.Mode.UNRESTRICTED;
        if (!unrestricted
                && (REPLACE_INTO.matcher(sql).find()
                        || ON_DUPLICATE.matcher(sql).find())) {
            throw new DataScopeViolationException("受控表上禁止 REPLACE / ON DUPLICATE KEY：" + msId);
        }
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException unparsable) {
            if (unrestricted) {
                return null;
            }
            throw new DataScopeViolationException("无法解析触达受控表的 SQL：" + msId, unparsable);
        }
        List<Long> collected = new ArrayList<>();
        values.set(collected);
        try {
            switch (statement) {
                case Select select -> processSelect(select, 0, sql, msId);
                case Update update -> {
                    guardUpdate(update, frame, msId);
                    processUpdate(update, 0, sql, msId);
                }
                case Delete delete -> {
                    guardDelete(delete, unrestricted, msId);
                    processDelete(delete, 0, sql, msId);
                }
                case Insert insert -> {
                    if (!unrestricted && insert.isUseDuplicate()) {
                        throw new DataScopeViolationException("受控表上禁止 ON DUPLICATE KEY UPDATE：" + msId);
                    }
                    if (insert.getSelect() != null) {
                        processSelect(insert.getSelect(), 0, sql, msId);
                    }
                }
                case Merge merge -> {
                    if (!unrestricted) {
                        throw new DataScopeViolationException("受控表上禁止 MERGE：" + msId);
                    }
                    return null;
                }
                default -> {
                    if (!unrestricted) {
                        throw new DataScopeViolationException("不支持在受控表上执行此类语句：" + msId);
                    }
                    return null;
                }
            }
        } finally {
            values.remove();
        }
        if (collected.isEmpty() && !(statement instanceof Update) && !(statement instanceof Delete)) {
            return null;
        }
        return new Rewritten(statement.toString(), List.copyOf(collected));
    }

    /** UPDATE：不允许改归属列（reassign 内除外）；多表 UPDATE 无法保证谓词落在正确的表上，直接拒绝。 */
    private void guardUpdate(Update update, DataScopes.Frame frame, String msId) {
        boolean reassign = frame != null && frame.mode() == DataScopes.Mode.REASSIGN;
        boolean controlled = registry.byTable(update.getTable().getName()).isPresent();
        if (controlled
                && ((update.getJoins() != null && !update.getJoins().isEmpty())
                        || (update.getStartJoins() != null
                                && !update.getStartJoins().isEmpty()))
                && !(frame != null && frame.mode() == DataScopes.Mode.UNRESTRICTED)) {
            throw new DataScopeViolationException("受控表上禁止多表 UPDATE：" + msId);
        }
        registry.byTable(update.getTable().getName()).ifPresent(resource -> {
            for (UpdateSet set : update.getUpdateSets()) {
                for (Object column : set.getColumns()) {
                    String name =
                            DataScopeRegistry.normalize(((net.sf.jsqlparser.schema.Column) column).getColumnName());
                    if (!reassign
                            && (name.equals(resource.ownerUserColumn()) || name.equals(resource.ownerDeptColumn()))) {
                        throw new DataScopeViolationException("禁止直接修改归属列，请使用 DataScopes.reassign：" + msId);
                    }
                }
            }
        });
    }

    /** DELETE：多表 DELETE 同样拒绝。 */
    private void guardDelete(Delete delete, boolean unrestricted, String msId) {
        boolean multi = (delete.getTables() != null && !delete.getTables().isEmpty())
                || (delete.getJoins() != null && !delete.getJoins().isEmpty())
                || (delete.getUsingList() != null && !delete.getUsingList().isEmpty());
        if (multi && !unrestricted) {
            throw new DataScopeViolationException("受控表上禁止多表 DELETE：" + msId);
        }
    }

    /** 为一张表生成谓词；非受控表、ALL 以及 unrestricted 作用域内返回 {@code null}。 */
    private Expression predicate(Table table, Expression where, String msId) {
        DataScopeResource resource = registry.byTable(table.getName()).orElse(null);
        if (resource == null) {
            return null;
        }
        DataScopes.Frame frame = DataScopes.frame();
        if (frame != null && frame.mode() == DataScopes.Mode.UNRESTRICTED) {
            return null;
        }
        KaiwuContext context = DataScopes.requireContext();
        ContextDataScope scope = context.dataScope();
        ContextDataScope.Rule rule = scope == null ? ContextDataScope.Rule.SELF_ONLY : scope.rule(resource.code());
        if (rule.all()) {
            return null;
        }
        long userId;
        try {
            userId = Long.parseLong(context.userId());
        } catch (NumberFormatException invalid) {
            throw new DataScopeViolationException("Context 用户 ID 不是数字");
        }
        String qualifier = table.getAlias() != null ? table.getAlias().getName() : table.getName();
        List<Long> collected = values.get();
        StringBuilder text = new StringBuilder("(")
                .append(qualifier)
                .append('.')
                .append(resource.ownerUserColumn())
                .append(" = ")
                .append(placeholder(collected, userId));
        if (!rule.deptIds().isEmpty()) {
            text.append(" OR ")
                    .append(qualifier)
                    .append('.')
                    .append(resource.ownerDeptColumn())
                    .append(" IN (");
            boolean first = true;
            for (Long dept : rule.deptIds().stream().sorted().toList()) {
                text.append(first ? "" : ", ").append(placeholder(collected, dept));
                first = false;
            }
            text.append(')');
        }
        text.append(')');
        try {
            return CCJSqlParserUtil.parseCondExpression(text.toString());
        } catch (JSQLParserException impossible) {
            throw new DataScopeViolationException("数据范围谓词生成失败", impossible);
        }
    }

    private static String placeholder(List<Long> collected, long value) {
        collected.add(value);
        return "?" + (PLACEHOLDER_BASE + collected.size() - 1);
    }
}
