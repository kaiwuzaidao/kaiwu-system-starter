package com.kaiwu.datascope;

import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;

/**
 * 按表自动追加数据范围谓词的 MyBatis-Plus 内部拦截器（ADR 0044），默认生效，不看方法而看表。
 *
 * <p>注册顺序约定为排在分页拦截器之前（{@link DataScopeStartupVerifier} 会在启动时检查）。改写发生在语句准备阶段，
 * 分页的 count 与列表各自被改写，口径一致；谓词里的值全部以绑定参数传入。</p>
 */
public final class DataScopeInnerInterceptor implements InnerInterceptor {

    private static final String PARAM_PREFIX = "__kaiwu_ds_";

    private final ScopeRewriter rewriter;

    /** @param registry 受控资源清单 */
    public DataScopeInnerInterceptor(DataScopeRegistry registry) {
        this.rewriter = new ScopeRewriter(registry);
    }

    /**
     * 在语句真正交给 JDBC 准备之前改写，覆盖 SELECT、INSERT、UPDATE、DELETE。
     *
     * <p>必须放在 {@code StatementHandler.prepare} 这一层，而不是 {@code Executor.query}：MyBatis-Plus 新版的
     * {@code selectOne(wrapper)}、游标查询、流式查询都不经过 {@code beforeQuery}，只放在那里会让这些路径整条
     * 绕过数据范围。分页的 count 查询与列表查询也各自在这里被改写，口径一致。</p>
     */
    @Override
    public void beforePrepare(StatementHandler sh, Connection connection, Integer transactionTimeout) {
        PluginUtils.MPStatementHandler handler = PluginUtils.mpStatementHandler(sh);
        MappedStatement ms = handler.mappedStatement();
        SqlCommandType type = ms.getSqlCommandType();
        if (type == SqlCommandType.SELECT
                || type == SqlCommandType.INSERT
                || type == SqlCommandType.UPDATE
                || type == SqlCommandType.DELETE) {
            apply(ms.getConfiguration(), ms.getId(), handler.mPBoundSql());
        }
    }

    private void apply(Configuration configuration, String msId, PluginUtils.MPBoundSql boundSql) {
        ScopeRewriter.Rewritten rewritten = rewriter.rewrite(boundSql.sql(), msId);
        if (rewritten == null) {
            return;
        }
        List<ParameterMapping> original = boundSql.parameterMappings();
        List<ParameterMapping> mappings =
                new ArrayList<>(original.size() + rewritten.values().size());
        Map<String, Object> additional = boundSql.additionalParameters();
        String sql = rebind(rewritten.sql(), original, mappings, rewritten.values(), configuration, additional);
        boundSql.sql(sql);
        boundSql.parameterMappings(mappings);
    }

    /**
     * 把 {@code ?1000000+k} 占位符换回 {@code ?}，同时按 SQL 中 {@code ?} 的出现顺序重排参数映射：
     * 原有参数保持相对顺序，新增参数插在占位符所在位置。任何数量对不上都失败关闭。
     */
    private static String rebind(
            String sql,
            List<ParameterMapping> original,
            List<ParameterMapping> out,
            List<Long> values,
            Configuration configuration,
            Map<String, Object> additional) {
        StringBuilder result = new StringBuilder(sql.length());
        int originalIndex = 0;
        int ours = 0;
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                result.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                result.append(c);
                continue;
            }
            if (c == '-' && sql.startsWith("--", i)) {
                int end = sql.indexOf('\n', i);
                end = end < 0 ? sql.length() : end;
                result.append(sql, i, end);
                i = end - 1;
                continue;
            }
            if (c == '/' && sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i);
                end = end < 0 ? sql.length() : end + 2;
                result.append(sql, i, end);
                i = end - 1;
                continue;
            }
            if (c != '?') {
                result.append(c);
                continue;
            }
            int j = i + 1;
            while (j < sql.length() && Character.isDigit(sql.charAt(j))) {
                j++;
            }
            result.append('?');
            if (j > i + 1) {
                int k = Integer.parseInt(sql.substring(i + 1, j)) - ScopeRewriter.PLACEHOLDER_BASE;
                if (k < 0 || k >= values.size()) {
                    throw new DataScopeViolationException("数据范围占位符越界");
                }
                String name = PARAM_PREFIX + k;
                additional.put(name, values.get(k));
                out.add(new ParameterMapping.Builder(configuration, name, Long.class).build());
                ours++;
                i = j - 1;
            } else {
                if (originalIndex >= original.size()) {
                    throw new DataScopeViolationException("改写后的参数个数与原语句不一致");
                }
                out.add(original.get(originalIndex++));
            }
        }
        if (originalIndex != original.size() || ours != values.size()) {
            throw new DataScopeViolationException("改写后的参数个数与原语句不一致");
        }
        return result.toString();
    }
}
