package com.kaiwu.datascope;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 受控资源清单，读取 {@code META-INF/kaiwu/data-scopes.yml}（System 的 docs/DATA_SCOPE.md §2）。
 *
 * <p>运行时只关心表与归属列；{@code allowedScopes} 等授权相关字段由 System 同步使用，这里只做形态校验。
 * 清单里出现的未知字段一律报错，不静默忽略——授权不随代码自动扩散。</p>
 */
public final class DataScopeRegistry {

    /** 清单在 classpath 上的位置。 */
    public static final String LOCATION = "META-INF/kaiwu/data-scopes.yml";

    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_-]*:[a-z][a-z0-9_-]*$");
    private static final Pattern IDENT = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final Set<String> SCOPES = Set.of("ALL", "DEPT_AND_CHILD", "DEPT", "SELF", "CUSTOM_DEPT");

    private final Map<String, DataScopeResource> byTable;
    private final Set<String> exemptTables;

    private DataScopeRegistry(Map<String, DataScopeResource> byTable, Set<String> exemptTables) {
        this.byTable = Map.copyOf(byTable);
        this.exemptTables = Set.copyOf(exemptTables);
    }

    /** 没有任何受控资源的空清单，仅用于模块被显式关闭或测试。 */
    public static DataScopeRegistry empty() {
        return new DataScopeRegistry(Map.of(), Set.of());
    }

    /**
     * 从输入流读取并校验清单。
     *
     * @param input data-scopes.yml 内容
     * @return 校验通过的清单
     * @throws IllegalStateException 清单结构不满足契约
     */
    public static DataScopeRegistry load(InputStream input) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object root = new Yaml(new SafeConstructor(options)).load(input);
        if (!(root instanceof Map<?, ?> top)) {
            throw new IllegalStateException("data-scopes.yml 必须是对象");
        }
        require(Set.of("resources", "exempt").containsAll(top.keySet()), "data-scopes.yml 含未知字段");
        Map<String, DataScopeResource> tables = new LinkedHashMap<>();
        Set<String> codes = new LinkedHashSet<>();
        for (Object item : list(top.get("resources"))) {
            Map<?, ?> row = map(item, "resources[]");
            require(
                    Set.of("code", "name", "table", "ownerUserColumn", "ownerDeptColumn", "allowedScopes")
                            .containsAll(row.keySet()),
                    "resources[] 含未知字段（授权字段不属于清单）");
            String code = text(row.get("code"), "code");
            require(CODE.matcher(code).matches() && code.length() <= 128, "资源码格式错误：" + code);
            require(codes.add(code), "资源码重复：" + code);
            require(row.get("name") instanceof String name && !name.isBlank(), "缺少 name：" + code);
            String table = ident(row.get("table"), "table", code);
            String user = row.containsKey("ownerUserColumn")
                    ? ident(row.get("ownerUserColumn"), "ownerUserColumn", code)
                    : "owner_user_id";
            String dept = row.containsKey("ownerDeptColumn")
                    ? ident(row.get("ownerDeptColumn"), "ownerDeptColumn", code)
                    : "owner_dept_id";
            require(!user.equals(dept), "归属人列与归属部门列不能相同：" + code);
            List<?> scopes = list(row.get("allowedScopes"));
            require(
                    !scopes.isEmpty() && scopes.size() <= 5 && SCOPES.containsAll(scopes) && scopes.contains("SELF"),
                    "allowedScopes 必须取自五种范围且包含 SELF：" + code);
            require(tables.put(table, new DataScopeResource(code, table, user, dept)) == null, "同一张表只能对应一个资源：" + table);
        }
        Set<String> exempt = new LinkedHashSet<>();
        for (Object item : list(top.get("exempt"))) {
            Map<?, ?> row = map(item, "exempt[]");
            require(Set.of("table", "reason").containsAll(row.keySet()), "exempt[] 含未知字段");
            String table = ident(row.get("table"), "table", "exempt");
            require(row.get("reason") instanceof String reason && !reason.isBlank(), "exempt 缺少理由：" + table);
            require(!tables.containsKey(table), "表不能同时是受控资源和豁免：" + table);
            exempt.add(table);
        }
        return new DataScopeRegistry(tables, exempt);
    }

    /**
     * @param table 表名，大小写与引号不敏感
     * @return 该表对应的受控资源；非受控表为空
     */
    public Optional<DataScopeResource> byTable(String table) {
        return Optional.ofNullable(byTable.get(normalize(table)));
    }

    /** @return 全部受控资源 */
    public Collection<DataScopeResource> resources() {
        return byTable.values();
    }

    /** @return 显式豁免的表（小写） */
    public Set<String> exemptTables() {
        return exemptTables;
    }

    /** 去掉反引号、双引号与 schema 前缀并转小写，使表名比较与引用方式无关。 */
    public static String normalize(String table) {
        String name = table;
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        return name.replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
    }

    private static List<?> list(Object value) {
        if (value == null) {
            return List.of();
        }
        require(value instanceof List<?>, "应为数组");
        return new ArrayList<>((List<?>) value);
    }

    private static Map<?, ?> map(Object value, String where) {
        require(value instanceof Map<?, ?>, where + " 必须是对象");
        return (Map<?, ?>) value;
    }

    private static String text(Object value, String field) {
        require(value instanceof String text && !text.isBlank(), "缺少 " + field);
        return (String) value;
    }

    private static String ident(Object value, String field, String where) {
        String text = text(value, field);
        require(IDENT.matcher(text).matches() && text.length() <= 64, field + " 格式错误：" + where);
        return text;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("data-scopes.yml 无效：" + message);
        }
    }
}
