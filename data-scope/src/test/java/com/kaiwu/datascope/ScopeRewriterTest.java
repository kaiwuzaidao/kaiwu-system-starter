package com.kaiwu.datascope;

import static com.kaiwu.datascope.MySqlHarness.as;
import static com.kaiwu.datascope.MySqlHarness.asNobody;
import static com.kaiwu.datascope.MySqlHarness.depts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 不依赖数据库的改写规则：谓词形态、绑定参数、被拒绝的语句形态。 */
class ScopeRewriterTest {

    private static ScopeRewriter rewriter;

    /** 以测试清单建立改写器，并安装不带数据库的 Context 来源。 */
    @BeforeAll
    static void setUp() throws Exception {
        rewriter = new ScopeRewriter(DataScopeRegistry.load(
                new ByteArrayInputStream(MySqlHarness.MANIFEST.getBytes(StandardCharsets.UTF_8))));
        MySqlHarness.install(userId -> new MemberDirectory.Placement(true, Set.of(10L)));
    }

    /** 清除 Context。 */
    @AfterEach
    void clear() {
        asNobody();
    }

    /** 谓词只用绑定参数：改写后的 SQL 里没有字面量 ID。 */
    @Test
    void predicateUsesBindParametersOnly() {
        as(1234567, 8888, Map.of("crm:customer", depts(8888L, 9999L)));

        ScopeRewriter.Rewritten rewritten = rewriter.rewrite("SELECT * FROM crm_customer c WHERE c.name = ?", "t");

        assertThat(rewritten.sql())
                .contains("c.owner_user_id = ?1000000")
                .contains("c.owner_dept_id IN (?1000001, ?1000002)")
                .doesNotContain("1234567", "8888", "9999");
        assertThat(rewritten.values()).containsExactly(1234567L, 8888L, 9999L);
    }

    /** 括号包住的 WHERE（MyBatis-Plus Wrapper 生成）同样被追加谓词。 */
    @Test
    void parenthesizedWhereGetsThePredicate() {
        as(1, 10, Map.of());
        ScopeRewriter.Rewritten rewritten =
                rewriter.rewrite("SELECT id,customer FROM crm_customer WHERE (id = ?)", "t");
        System.out.println("REWRITTEN=" + (rewritten == null ? null : rewritten.sql()));
        assertThat(rewritten).isNotNull();
        assertThat(rewritten.sql()).contains("owner_user_id = ?1000000");
    }

    /** ALL 不追加条件；非受控表与无关语句原样放行。 */
    @Test
    void allAndUnrelatedStatementsAreUntouched() {
        as(1, 10, Map.of("crm:customer", MySqlHarness.all()));
        assertThat(rewriter.rewrite("SELECT * FROM crm_customer", "t")).isNull();
        assertThat(rewriter.rewrite("SELECT * FROM crm_industry", "t")).isNull();
        asNobody();
        assertThat(rewriter.rewrite("SELECT * FROM crm_industry", "t")).isNull();
    }

    /** 多表 UPDATE / DELETE 和 MERGE 在受控表上被拒绝。 */
    @Test
    void unsupportedShapesAreRefused() {
        as(1, 10, Map.of());
        for (String sql : new String[] {
            "UPDATE crm_customer c JOIN crm_contract k ON k.customer_id = c.id SET c.name = 'x'",
            "DELETE c FROM crm_customer c JOIN crm_contract k ON k.customer_id = c.id",
            "MERGE INTO crm_customer t USING crm_contract s ON t.id = s.customer_id WHEN MATCHED THEN UPDATE SET t.name = 'x'",
            "TRUNCATE TABLE crm_customer",
        }) {
            assertThatThrownBy(() -> rewriter.rewrite(sql, "t"))
                    .as(sql)
                    .isInstanceOf(DataScopeViolationException.class);
        }
    }

    /** unrestricted 作用域内不追加谓词，也不抛 MERGE 的异常。 */
    @Test
    void unrestrictedScopeSkipsRewriting() {
        asNobody();
        DataScopes.unrestricted("对账", () -> {
            assertThat(rewriter.rewrite("SELECT * FROM crm_customer", "t")).isNull();
            assertThat(rewriter.rewrite(
                            "MERGE INTO crm_customer t USING crm_contract s ON t.id = s.customer_id "
                                    + "WHEN MATCHED THEN UPDATE SET t.name = 'x'",
                            "t"))
                    .isNull();
        });
    }
}
