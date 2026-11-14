package com.kaiwu.starter.auth.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaiwu.starter.ContextVerificationException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** {@code ds} 声明的解析、规范化校验与指纹（System docs/DATA_SCOPE.md §4）。 */
class ContextDataScopeTest {

    /** 契约 §4.4 的共用测试向量，System 侧测试断言同一组值。 */
    private static final String VECTOR =
            """
            {"v":1,"primaryDeptId":"101","depts":["101","102","105"],"rules":{"crm:contract":{"all":true},"crm:customer":{"set":[0,1,2]},"crm:followup":{"set":[]}},"fp":"d361b479f54b4be2"}""";

    /** 把 JSON 文本解析成与 JWT 声明同形的 Map。 */
    private static Object json(String text) {
        return JsonMapper.builder().build().readValue(text, Map.class);
    }

    /** 共用测试向量能被解析，指纹与契约一致。 */
    @Test
    void parsesTheSharedVector() {
        ContextDataScope scope = ContextDataScope.parse(json(VECTOR));

        assertThat(scope.fingerprint()).isEqualTo("d361b479f54b4be2");
        assertThat(scope.primaryDeptId()).isEqualTo(101L);
        assertThat(scope.rule("crm:contract").all()).isTrue();
        assertThat(scope.rule("crm:customer").deptIds()).containsExactlyInAnyOrder(101L, 102L, 105L);
        assertThat(scope.rule("crm:followup").all()).isFalse();
        assertThat(scope.rule("crm:followup").deptIds()).isEmpty();
    }

    /** 缺失 ds 为 null；资源缺条目按仅本人。 */
    @Test
    void missingDsIsNullAndMissingResourceIsSelfOnly() {
        assertThat(ContextDataScope.parse(null)).isNull();
        assertThat(ContextDataScope.parse(json(VECTOR)).rule("crm:other")).isEqualTo(ContextDataScope.Rule.SELF_ONLY);
    }

    /** 任何结构或规范化违规都失败关闭，不退化成「没有范围」。 */
    @Test
    void rejectsEveryContractViolation() {
        String[] bad = {
            VECTOR.replace("d361b479f54b4be2", "0000000000000000"),
            VECTOR.replace("\"v\":1", "\"v\":2"),
            VECTOR.replace("\"depts\":[\"101\",\"102\",\"105\"]", "\"depts\":[\"102\",\"101\",\"105\"]"),
            VECTOR.replace("[0,1,2]", "[0,1,3]"),
            VECTOR.replace("[0,1,2]", "[2,1,0]"),
            VECTOR.replace("\"105\"", "\"105\",\"999\""),
            VECTOR.replace("\"101\",\"102\"", "\"101\",\"99999999999999999999\""),
            VECTOR.replace("\"v\":1", "\"v\":1,\"extra\":true"),
            VECTOR.replace("{\"all\":true}", "{\"all\":false}"),
            VECTOR.replace("crm:contract", "Crm:Contract"),
        };
        for (String text : bad) {
            assertThatThrownBy(() -> ContextDataScope.parse(json(text)))
                    .as(text)
                    .isInstanceOf(ContextVerificationException.class);
        }
        assertThatThrownBy(() -> ContextDataScope.parse("not-an-object"))
                .isInstanceOf(ContextVerificationException.class);
    }
}
