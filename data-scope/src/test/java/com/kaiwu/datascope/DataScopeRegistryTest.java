package com.kaiwu.datascope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 清单解析：契约 §2 的字段约束，未知字段与授权字段一律报错。 */
class DataScopeRegistryTest {

    private static DataScopeRegistry load(String yaml) throws Exception {
        return DataScopeRegistry.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static final String VALID =
            """
            resources:
              - code: crm:customer
                name: 客户
                table: crm_customer
                allowedScopes: [ALL, SELF]
            exempt:
              - { table: crm_industry, reason: 字典 }
            """;

    /** 合法清单：默认归属列，表名比较与引号、大小写无关。 */
    @Test
    void loadsValidManifestWithDefaults() throws Exception {
        DataScopeRegistry registry = load(VALID);

        DataScopeResource resource = registry.byTable("`CRM_Customer`").orElseThrow();
        assertThat(resource.code()).isEqualTo("crm:customer");
        assertThat(resource.ownerUserColumn()).isEqualTo("owner_user_id");
        assertThat(resource.ownerDeptColumn()).isEqualTo("owner_dept_id");
        assertThat(registry.byTable("db.crm_customer")).isPresent();
        assertThat(registry.byTable("crm_industry")).isEmpty();
        assertThat(registry.exemptTables()).containsExactly("crm_industry");
    }

    /** 违反契约的清单都被拒绝。 */
    @Test
    void rejectsContractViolations() {
        String[] bad = {
            VALID.replace("allowedScopes: [ALL, SELF]", "allowedScopes: [ALL]"),
            VALID.replace("allowedScopes: [ALL, SELF]", "allowedScopes: [SELF, ORG]"),
            VALID.replace("allowedScopes: [ALL, SELF]", "allowedScopes: [SELF]\n    defaultScope: ALL"),
            VALID.replace("code: crm:customer", "code: customer"),
            VALID.replace("table: crm_customer", "table: Crm-Customer"),
            VALID.replace("table: crm_industry", "table: crm_customer"),
            VALID.replace("{ table: crm_industry, reason: 字典 }", "{ table: crm_industry, reason: '' }"),
            VALID + "extra: 1\n",
            VALID.replace("table: crm_customer", "table: crm_customer\n    ownerDeptColumn: owner_user_id"),
        };
        for (String yaml : bad) {
            assertThatThrownBy(() -> load(yaml)).as(yaml).isInstanceOf(IllegalStateException.class);
        }
    }
}
