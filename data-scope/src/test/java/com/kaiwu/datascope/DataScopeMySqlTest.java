package com.kaiwu.datascope;

import static com.kaiwu.datascope.MySqlHarness.all;
import static com.kaiwu.datascope.MySqlHarness.as;
import static com.kaiwu.datascope.MySqlHarness.asNobody;
import static com.kaiwu.datascope.MySqlHarness.asWithoutDs;
import static com.kaiwu.datascope.MySqlHarness.depts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaiwu.datascope.Mappers.Contract;
import com.kaiwu.datascope.Mappers.ContractMapper;
import com.kaiwu.datascope.Mappers.Customer;
import com.kaiwu.datascope.Mappers.CustomerMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 真实 MySQL 上验证自动改写（设计稿 §8.2 逐行）。
 *
 * <p>夹具：用户 1、2 在部门 10，用户 3 在部门 20；客户 A(1,10) B(2,10) C(3,20) D(4,30) E(5,0)；
 * 每个 A–D 各有一份合同，归属与客户相同。</p>
 */
class DataScopeMySqlTest {

    private static MySqlHarness harness;
    private static CustomerMapper customers;
    private static ContractMapper contracts;
    private long a;
    private long b;
    private long c;

    /** 启动容器并装配。 */
    @BeforeAll
    static void start() throws Exception {
        harness = new MySqlHarness();
        customers = harness.mapper(CustomerMapper.class);
        contracts = harness.mapper(ContractMapper.class);
    }

    /** 关闭容器。 */
    @AfterAll
    static void stop() {
        harness.close();
    }

    /** 每个用例前重建夹具并清除 Context。 */
    @BeforeEach
    void fixtures() throws Exception {
        asNobody();
        harness.reset();
        a = harness.customer("A", 1, 10);
        b = harness.customer("B", 2, 10);
        c = harness.customer("C", 3, 20);
        long d = harness.customer("D", 4, 30);
        harness.customer("E", 5, 0);
        harness.contract(a, "kA", 1, 10);
        harness.contract(b, "kB", 2, 10);
        harness.contract(c, "kC", 3, 20);
        harness.contract(d, "kD", 4, 30);
        harness.directory = userId -> Set.of("2", "3").contains(userId)
                ? new MemberDirectory.Placement(true, userId.equals("2") ? Set.of(10L) : Set.of(20L))
                : new MemberDirectory.Placement(false, Set.of());
    }

    /** 清除线程上的 Context。 */
    @AfterEach
    void clear() {
        asNobody();
    }

    private List<String> names() {
        return customers.selectList(null).stream().map(x -> x.name).sorted().toList();
    }

    /** 五种归约形态下列表的可见行。 */
    @Test
    void listsFollowTheReducedScope() {
        as(1, 10, Map.of());
        assertThat(names()).containsExactly("A");
        as(1, 10, Map.of("crm:customer", depts(10L)));
        assertThat(names()).containsExactly("A", "B");
        as(1, 10, Map.of("crm:customer", depts(10L, 20L)));
        assertThat(names()).containsExactly("A", "B", "C");
        as(1, 10, Map.of("crm:customer", all()));
        assertThat(names()).containsExactly("A", "B", "C", "D", "E");
        as(5, 0, Map.of());
        assertThat(names()).containsExactly("E");
    }

    /** Context 没有 ds，或 ds 缺该资源时按仅本人。 */
    @Test
    void missingDsOrMissingResourceMeansSelf() {
        asWithoutDs(1);
        assertThat(names()).containsExactly("A");
        as(1, 10, Map.of("crm:contract", all()));
        assertThat(names()).containsExactly("A");
    }

    /** selectById 越权返回 null，可见则返回实体。 */
    @Test
    void selectByIdOfInvisibleRowReturnsNull() {
        as(1, 10, Map.of());
        assertThat(customers.selectById(b)).isNull();
        assertThat(customers.selectById(a)).isNotNull();
    }

    /** Wrapper 带条件的 selectOne（WHERE (id = ?)）同样被改写，越权返回 null。 */
    @Test
    void selectOneWithWrapperConditionIsFiltered() {
        as(1, 10, Map.of());
        assertThat(customers.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Customer>().eq("id", b)))
                .isNull();
        assertThat(customers.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Customer>().eq("id", a)))
                .isNotNull();
    }

    /** 游标查询和 ResultHandler 查询不经过 Executor.query 的拦截点，同样被过滤。 */
    @Test
    void cursorAndResultHandlerQueriesAreFiltered() throws Exception {
        as(1, 10, Map.of("crm:customer", depts(10L)));
        List<String> viaCursor = new java.util.ArrayList<>();
        try (var cursor = customers.cursor()) {
            cursor.forEach(viaCursor::add);
        }
        assertThat(viaCursor).containsExactly("A", "B");
        List<String> viaHandler = new java.util.ArrayList<>();
        customers.selectList(null, context -> viaHandler.add(context.getResultObject().name));
        assertThat(viaHandler).containsExactlyInAnyOrder("A", "B");
    }

    /** 分页的 count 与列表口径一致。 */
    @Test
    void paginationCountMatchesVisibleRows() {
        as(1, 10, Map.of("crm:customer", depts(10L, 20L)));
        Page<Customer> page = customers.selectPage(new Page<>(1, 2), null);
        assertThat(page.getTotal()).isEqualTo(3);
        assertThat(page.getRecords()).hasSize(2);
        assertThat(customers.selectCount(null)).isEqualTo(3);
    }

    /** 手写 SQL 里原有参数（含 LIMIT）与新增谓词参数的顺序不乱。 */
    @Test
    void existingParametersKeepTheirOrderAroundTheInsertedPredicate() {
        as(1, 10, Map.of("crm:customer", depts(10L)));
        assertThat(customers.byPrefix("%", 5)).containsExactly("A", "B");
        assertThat(customers.byPrefix("%", 1)).containsExactly("A");
        assertThat(customers.byPrefix("B%", 5)).containsExactly("B");
    }

    /** 两张受控表 INNER JOIN 取交集。 */
    @Test
    void innerJoinIntersectsBothTables() {
        as(1, 10, Map.of("crm:customer", depts(10L, 20L), "crm:contract", depts(10L)));
        assertThat(customers.innerJoin())
                .extracting(row -> row.get("customer") + "/" + row.get("contract"))
                .containsExactly("A/kA", "B/kB");
    }

    /** LEFT JOIN 受控表：谓词在 ON 里，左表行保留，不可见的右表行变 NULL。 */
    @Test
    void leftJoinKeepsLeftRowsAndNullsInvisibleRightRows() {
        as(1, 10, Map.of("crm:customer", depts(10L, 20L), "crm:contract", depts(10L)));
        assertThat(customers.leftJoin())
                .extracting(row -> row.get("customer") + "/" + row.get("contract"))
                .containsExactly("A/kA", "B/kB", "C/null");
    }

    /** 子查询与 UNION 里的受控表同样被过滤。 */
    @Test
    void subqueriesAndUnionsAreFiltered() {
        as(1, 10, Map.of("crm:customer", depts(10L, 20L), "crm:contract", depts(10L)));
        assertThat(customers.inSubquery()).containsExactly("A", "B");
        assertThat(customers.union()).containsExactly("A", "B", "C", "kA", "kB");
    }

    /** UPDATE / DELETE 越权行影响 0 行，可见行影响 1 行。 */
    @Test
    void updatesAndDeletesOnInvisibleRowsAffectNothing() throws Exception {
        as(1, 10, Map.of());
        assertThat(customers.rename(b, "B2")).isZero();
        assertThat(customers.remove(b)).isZero();
        Customer forged = new Customer();
        forged.id = b;
        forged.name = "B3";
        assertThat(customers.updateById(forged)).isZero();
        assertThat(customers.deleteById(b)).isZero();
        assertThat(harness.scalar("SELECT COUNT(*) FROM crm_customer WHERE name = 'B'"))
                .isEqualTo(1);
        assertThat(customers.rename(a, "A2")).isEqualTo(1);
        assertThat(customers.remove(a)).isEqualTo(1);
    }

    /** 直接修改归属列被拒绝，reassign 内允许，且源行仍受当前用户可见性约束。 */
    @Test
    void ownerColumnsChangeOnlyThroughReassign() throws Exception {
        as(1, 10, Map.of());
        assertThatThrownBy(() -> customers.setOwner(a, 2)).satisfies(DataScopeMySqlTest::isViolation);

        int changed = DataScopes.reassign("转交给同事", new DataScopes.Owner(2, 10), () -> customers.reassign(a, 2, 10));
        assertThat(changed).isEqualTo(1);
        assertThat(harness.scalar("SELECT owner_user_id FROM crm_customer WHERE id = " + a))
                .isEqualTo(2);

        int notMine = DataScopes.reassign("越权转交", new DataScopes.Owner(2, 10), () -> customers.reassign(b, 2, 10));
        assertThat(notMine).isZero();
        assertThat(harness.scalar("SELECT owner_user_id FROM crm_customer WHERE id = " + b))
                .isEqualTo(2);
    }

    /** 批量交接同样只作用于当前用户可见的行。 */
    @Test
    void batchHandoverOnlyTouchesVisibleRows() throws Exception {
        harness.customer("A2", 1, 99);
        as(1, 10, Map.of());
        int changed =
                DataScopes.reassign("销售 A 调岗交接", new DataScopes.Owner(3, 20), () -> customers.reassignAll(1, 3, 20));
        assertThat(changed).isEqualTo(2);
    }

    /** 新归属必须是有效成员、所属部门之一，且都不能为 0；reason 必填。 */
    @Test
    void reassignValidatesTheNewOwner() {
        as(1, 10, Map.of());
        Runnable body = () -> {};
        assertThatThrownBy(() -> DataScopes.reassign("x", new DataScopes.Owner(9, 10), body))
                .isInstanceOf(DataScopeReassignRejectedException.class)
                .hasMessageContaining("有效成员");
        assertThatThrownBy(() -> DataScopes.reassign("x", new DataScopes.Owner(2, 20), body))
                .isInstanceOf(DataScopeReassignRejectedException.class)
                .hasMessageContaining("所属部门");
        assertThatThrownBy(() -> DataScopes.reassign("x", new DataScopes.Owner(2, 0), body))
                .isInstanceOf(DataScopeViolationException.class);
        assertThatThrownBy(() -> DataScopes.reassign("x", new DataScopes.Owner(0, 10), body))
                .isInstanceOf(DataScopeViolationException.class);
        assertThatThrownBy(() -> DataScopes.reassign(" ", new DataScopes.Owner(2, 10), body))
                .isInstanceOf(DataScopeViolationException.class);
        asNobody();
        assertThatThrownBy(() -> DataScopes.reassign("x", new DataScopes.Owner(2, 10), body))
                .isInstanceOf(DataScopeViolationException.class);
    }

    /** 没有配置 MemberDirectory 时 reassign 一律失败。 */
    @Test
    void reassignFailsClosedWithoutMemberDirectory() {
        as(1, 10, Map.of());
        DataScopes.configure(() -> null, null);
        try {
            MySqlHarness.install(null);
            assertThatThrownBy(() -> DataScopes.reassign("x", new DataScopes.Owner(2, 10), () -> {}))
                    .isInstanceOf(DataScopeViolationException.class);
        } finally {
            MySqlHarness.install(userId -> harness.directory.placement(userId));
        }
    }

    /** upsert 类语句在受控表上被拒绝，unrestricted 内允许。 */
    @Test
    void upsertStatementsAreRejectedUnlessUnrestricted() throws Exception {
        as(1, 10, Map.of("crm:customer", all()));
        assertThatThrownBy(() -> customers.upsert("A")).satisfies(DataScopeMySqlTest::isViolation);
        assertThatThrownBy(() -> customers.replace("A")).satisfies(DataScopeMySqlTest::isViolation);
        assertThat(harness.scalar("SELECT owner_user_id FROM crm_customer WHERE name = 'A'"))
                .isEqualTo(1);

        asNobody();
        DataScopes.unrestricted("外部同步任务", () -> customers.upsert("A"));
        assertThat(harness.scalar("SELECT owner_user_id FROM crm_customer WHERE name = 'A'"))
                .isEqualTo(9);
        DataScopes.unrestricted("外部同步任务", () -> customers.replace("Z"));
        assertThat(harness.scalar("SELECT COUNT(*) FROM crm_customer WHERE name = 'Z'"))
                .isEqualTo(1);
    }

    /** INSERT … SELECT 的源查询带谓词，不能把不可见的数据复制成自己的。 */
    @Test
    void insertSelectOnlyCopiesVisibleRows() throws Exception {
        as(1, 10, Map.of());
        assertThat(customers.copyVisible()).isEqualTo(1);
        assertThat(harness.scalar("SELECT COUNT(*) FROM crm_customer WHERE name LIKE '%-copy'"))
                .isEqualTo(1);
    }

    /** 没有 Context 的线程触达受控表被拒绝；unrestricted 内放行；豁免表不受影响。 */
    @Test
    void threadsWithoutContextAreRefusedUnlessUnrestricted() {
        asNobody();
        assertThatThrownBy(() -> customers.selectList(null)).satisfies(DataScopeMySqlTest::isViolation);
        assertThatThrownBy(() -> customers.rename(a, "x")).satisfies(DataScopeMySqlTest::isViolation);
        assertThat(DataScopes.unrestricted("每日客户公海回收任务", (Supplier<List<Customer>>) () -> customers.selectList(null)))
                .hasSize(5);
        assertThat(customers.industries()).containsExactly("IT", "制造");
        assertThatThrownBy(() -> DataScopes.unrestricted("", () -> {})).isInstanceOf(DataScopeViolationException.class);
    }

    /** 无法解析的 SQL 触达受控表时失败关闭。 */
    @Test
    void unparsableSqlOnControlledTableIsRefused() {
        as(1, 10, Map.of("crm:customer", all()));
        assertThatThrownBy(() -> customers.unparsable()).satisfies(DataScopeMySqlTest::isViolation);
    }

    /** 普通路径插入以 Context 覆盖请求体传入的归属；没有部门时取 0。 */
    @Test
    void insertOverridesForgedOwnerFromContext() throws Exception {
        as(1, 10, Map.of());
        Customer forged = customer("N1", 999L, 999L);
        customers.insert(forged);
        assertThat(harness.scalar("SELECT owner_user_id FROM crm_customer WHERE name = 'N1'"))
                .isEqualTo(1);
        assertThat(harness.scalar("SELECT owner_dept_id FROM crm_customer WHERE name = 'N1'"))
                .isEqualTo(10);
        as(7, 0, Map.of());
        customers.insert(customer("N2", null, null));
        assertThat(harness.scalar("SELECT owner_dept_id FROM crm_customer WHERE name = 'N2'"))
                .isZero();
        asNobody();
        assertThatThrownBy(() -> customers.insert(customer("N3", null, null)))
                .satisfies(DataScopeMySqlTest::isViolation);
    }

    /** reassign 内插入以新归属为准且不得与实体值冲突；unrestricted 内必须显式提供归属。 */
    @Test
    void insertInsideReassignAndUnrestrictedScopes() throws Exception {
        as(1, 10, Map.of());
        DataScopes.reassign("代建客户", new DataScopes.Owner(2, 10), () -> customers.insert(customer("R1", null, null)));
        assertThat(harness.scalar("SELECT owner_user_id FROM crm_customer WHERE name = 'R1'"))
                .isEqualTo(2);
        assertThatThrownBy(() -> DataScopes.reassign(
                        "代建客户", new DataScopes.Owner(2, 10), () -> customers.insert(customer("R2", 3L, 20L))))
                .satisfies(DataScopeMySqlTest::isViolation);

        asNobody();
        assertThatThrownBy(() -> DataScopes.unrestricted("导入", () -> customers.insert(customer("U1", null, null))))
                .satisfies(DataScopeMySqlTest::isViolation);
        DataScopes.unrestricted("导入", () -> customers.insert(customer("U2", 4L, 30L)));
        assertThat(harness.scalar("SELECT owner_dept_id FROM crm_customer WHERE name = 'U2'"))
                .isEqualTo(30);
    }

    /** 合同表同样受控，受控实体插入也按 Context 覆盖。 */
    @Test
    void secondControlledEntityBehavesTheSame() throws Exception {
        as(1, 10, Map.of());
        Contract contract = new Contract();
        contract.customerId = a;
        contract.title = "kNew";
        contracts.insert(contract);
        assertThat(contracts.selectList(null)).extracting(x -> x.title).containsExactly("kA", "kNew");
    }

    private static Customer customer(String name, Long user, Long dept) {
        Customer customer = new Customer();
        customer.name = name;
        customer.ownerUserId = user;
        customer.ownerDeptId = dept;
        return customer;
    }

    /** 异常链里含数据范围违规。 */
    private static void isViolation(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof DataScopeViolationException) {
                return;
            }
        }
        throw new AssertionError("异常链中没有 DataScopeViolationException", failure);
    }
}
