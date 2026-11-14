package com.kaiwu.starter.auth.model;

import com.kaiwu.starter.ContextVerificationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Gateway 在 Context 声明 {@code ds} 中带来的数据范围（System 的 docs/DATA_SCOPE.md §4–§5）。
 *
 * <p>解析是失败关闭的：{@code ds} 存在但不满足结构、规范化顺序或指纹校验时，整个 Context 验证失败，
 * 而不是退化成「没有范围」——后者会让受控资源按 SELF 以外的方式被误读。资源在 {@code rules} 里缺失时
 * 由 {@link #rule(String)} 返回 {@link Rule#SELF_ONLY}，即平台固定默认的仅本人。</p>
 *
 * @param primaryDeptId 用户主部门；没有任何所属部门时为 {@code null}，写入归属部门时取 0
 * @param rules         资源码到规则，键为 {@code 模块:资源}
 * @param fingerprint   去掉自身后规范化字节的 SHA-256 前 16 位十六进制，可作缓存键
 */
public record ContextDataScope(Long primaryDeptId, Map<String, Rule> rules, String fingerprint) {

    private static final Pattern ID = Pattern.compile("^[0-9]{1,19}$");
    private static final Pattern RESOURCE = Pattern.compile("^[a-z][a-z0-9_-]*:[a-z][a-z0-9_-]*$");
    private static final Pattern FP = Pattern.compile("^[0-9a-f]{16}$");

    /** 复制规则表，保证记录不可变。 */
    public ContextDataScope {
        rules = Map.copyOf(rules);
    }

    /**
     * 某个资源上的规则：要么全部可见，要么限定在部门集合内（并始终包含本人数据）。
     *
     * @param all     是否项目内全部可见
     * @param deptIds 可见部门 ID；{@code all} 为真时为空，空集合表示仅本人
     */
    public record Rule(boolean all, Set<Long> deptIds) {

        /** 平台固定默认：仅本人，不含任何部门。 */
        public static final Rule SELF_ONLY = new Rule(false, Set.of());

        /** 复制部门集合，保证记录不可变。 */
        public Rule {
            deptIds = Set.copyOf(deptIds);
        }
    }

    /**
     * 取资源的规则；Context 没有该资源的条目时按仅本人。
     *
     * @param resourceCode 受控资源码
     * @return 永不为 {@code null}
     */
    public Rule rule(String resourceCode) {
        return rules.getOrDefault(resourceCode, Rule.SELF_ONLY);
    }

    /**
     * 解析并校验 JWT 声明 {@code ds}。
     *
     * @param raw 声明的原始值；缺失时返回 {@code null}（所有资源按仅本人）
     * @return 解析结果，缺失时为 {@code null}
     * @throws ContextVerificationException 结构、顺序或指纹不满足契约
     */
    public static ContextDataScope parse(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw invalid("ds 必须是对象");
        }
        require(map.keySet().stream().allMatch(key -> key instanceof String), "ds 键必须是字符串");
        Set<?> allowed = Set.of("v", "primaryDeptId", "depts", "rules", "fp");
        require(allowed.containsAll(map.keySet()), "ds 含未知字段");
        require(Integer.valueOf(1).equals(integer(map.get("v"))), "ds 版本不受支持");
        String fp = map.get("fp") instanceof String text ? text : null;
        require(fp != null && FP.matcher(fp).matches(), "ds.fp 非法");

        Long primary = null;
        if (map.containsKey("primaryDeptId")) {
            primary = id(map.get("primaryDeptId"), "ds.primaryDeptId 非法");
        }
        List<Long> depts = new ArrayList<>();
        require(map.get("depts") instanceof Collection<?>, "ds.depts 必须是数组");
        for (Object item : (Collection<?>) map.get("depts")) {
            depts.add(id(item, "ds.depts 含非法部门 ID"));
        }
        for (int i = 1; i < depts.size(); i++) {
            require(depts.get(i - 1) < depts.get(i), "ds.depts 必须数值升序且不重复");
        }

        require(map.get("rules") instanceof Map<?, ?>, "ds.rules 必须是对象");
        Map<?, ?> rawRules = (Map<?, ?>) map.get("rules");
        Map<String, Rule> rules = new LinkedHashMap<>();
        Map<String, List<Integer>> sets = new TreeMap<>();
        Set<Integer> referenced = new LinkedHashSet<>();
        String previous = null;
        for (Map.Entry<?, ?> entry : rawRules.entrySet()) {
            require(entry.getKey() instanceof String, "ds.rules 键必须是字符串");
            String resource = (String) entry.getKey();
            require(RESOURCE.matcher(resource).matches(), "ds.rules 含非法资源码");
            require(previous == null || previous.compareTo(resource) < 0, "ds.rules 键必须字典序且不重复");
            previous = resource;
            require(entry.getValue() instanceof Map<?, ?>, "ds.rules 条目必须是对象");
            Map<?, ?> rule = (Map<?, ?>) entry.getValue();
            if (rule.size() == 1 && Boolean.TRUE.equals(rule.get("all"))) {
                rules.put(resource, new Rule(true, Set.of()));
                sets.put(resource, null);
            } else if (rule.size() == 1 && rule.get("set") instanceof Collection<?> indexes) {
                List<Integer> list = new ArrayList<>();
                for (Object index : indexes) {
                    Integer value = integer(index);
                    require(value != null && value >= 0 && value < depts.size(), "ds 部门下标越界");
                    require(list.isEmpty() || list.getLast() < value, "ds 部门下标必须升序且不重复");
                    list.add(value);
                }
                referenced.addAll(list);
                Set<Long> ids = new LinkedHashSet<>();
                list.forEach(index -> ids.add(depts.get(index)));
                rules.put(resource, new Rule(false, ids));
                sets.put(resource, list);
            } else {
                throw invalid("ds 规则必须是 {all:true} 或 {set:[...]}");
            }
        }
        require(referenced.size() == depts.size(), "ds.depts 含未被引用的部门");
        require(fingerprint(primary, depts, sets).equals(fp), "ds 指纹不一致");
        return new ContextDataScope(primary, rules, fp);
    }

    /** 按契约 §4.3 重新生成规范化紧凑 JSON（不含 fp）并取 SHA-256 前 16 位十六进制。 */
    private static String fingerprint(Long primary, List<Long> depts, Map<String, List<Integer>> sets) {
        StringBuilder json = new StringBuilder("{\"v\":1");
        if (primary != null) {
            json.append(",\"primaryDeptId\":\"").append(primary).append('"');
        }
        json.append(",\"depts\":[");
        for (int i = 0; i < depts.size(); i++) {
            json.append(i == 0 ? "" : ",").append('"').append(depts.get(i)).append('"');
        }
        json.append("],\"rules\":{");
        boolean first = true;
        for (Map.Entry<String, List<Integer>> entry : sets.entrySet()) {
            json.append(first ? "" : ",").append('"').append(entry.getKey()).append("\":");
            first = false;
            if (entry.getValue() == null) {
                json.append("{\"all\":true}");
            } else {
                json.append("{\"set\":[");
                List<Integer> list = entry.getValue();
                for (int i = 0; i < list.size(); i++) {
                    json.append(i == 0 ? "" : ",").append(list.get(i));
                }
                json.append("]}");
            }
        }
        json.append("}}");
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(json.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 部门 ID 在 JSON 里是十进制字符串；超出 long 范围按非法处理。 */
    private static Long id(Object value, String message) {
        require(value instanceof String text && ID.matcher(text).matches(), message);
        try {
            return Long.parseLong((String) value);
        } catch (NumberFormatException overflow) {
            throw invalid(message);
        }
    }

    /** 只接受整数；JJWT 解析出的小整数是 Integer，大数可能是 Long。 */
    private static Integer integer(Object value) {
        if (value instanceof Integer number) {
            return number;
        }
        if (value instanceof Long number && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
            return number.intValue();
        }
        return null;
    }

    /** 条件不满足时按「Context 声明不完整」失败关闭。 */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw invalid(message);
        }
    }

    /** 构造验证失败异常；消息不含部门 ID。 */
    private static ContextVerificationException invalid(String message) {
        return new ContextVerificationException(message);
    }
}
