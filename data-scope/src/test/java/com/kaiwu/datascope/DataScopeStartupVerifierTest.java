package com.kaiwu.datascope;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import org.apache.ibatis.reflection.MetaObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** 启动自检：接了模块却没真正生效的几种形态都必须让应用起不来。 */
class DataScopeStartupVerifierTest {

    private static MySqlHarness harness;

    /** 缺少归属注解的实体，对应单独的表，避免污染其他用例使用的全局表元数据。 */
    @TableName("crm_bad")
    static class BadEntity {
        @TableId(type = IdType.AUTO)
        Long id;

        Long ownerUserId;
        Long ownerDeptId;
    }

    /** 坏实体的 Mapper。 */
    interface BadMapper extends BaseMapper<BadEntity> {}

    /** 启动容器并登记坏实体。 */
    @BeforeAll
    static void start() throws Exception {
        harness = new MySqlHarness();
        try (Connection connection = harness.dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS crm_bad (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                    + "owner_user_id BIGINT NOT NULL, owner_dept_id BIGINT NOT NULL)");
        }
        harness.sessions.getConfiguration().addMapper(BadMapper.class);
    }

    /** 关闭容器。 */
    @AfterAll
    static void stop() {
        harness.close();
    }

    private MetaObjectHandler fillingHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                harness.filler.fill(metaObject);
            }

            @Override
            public void updateFill(MetaObject metaObject) {}
        };
    }

    private MetaObjectHandler idleHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {}

            @Override
            public void updateFill(MetaObject metaObject) {}
        };
    }

    private DataScopeStartupVerifier verifier(
            DataScopeRegistry registry, MybatisPlusInterceptor holder, MetaObjectHandler handler) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (holder != null) {
            beans.registerSingleton("holder", holder);
        }
        if (handler != null) {
            beans.registerSingleton("handler", handler);
        }
        beans.registerSingleton("dataSource", harness.dataSource);
        return new DataScopeStartupVerifier(
                registry,
                harness.interceptor,
                beans.getBeanProvider(MybatisPlusInterceptor.class),
                beans.getBeanProvider(MetaObjectHandler.class),
                beans.getBeanProvider(javax.sql.DataSource.class));
    }

    private static DataScopeRegistry registry(String yaml) throws Exception {
        return DataScopeRegistry.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    /** 全部就绪时通过。 */
    @Test
    void passesWhenEverythingIsWired() {
        assertThatCode(() -> verifier(harness.registry, harness.mybatisInterceptor, fillingHandler())
                        .afterSingletonsInstantiated())
                .doesNotThrowAnyException();
    }

    /** 拦截器没有加入 MybatisPlusInterceptor，或没有该 Bean，启动失败。 */
    @Test
    void failsWhenInterceptorIsNotRegistered() {
        MybatisPlusInterceptor empty = new MybatisPlusInterceptor();
        empty.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        assertThatThrownBy(() ->
                        verifier(harness.registry, empty, fillingHandler()).afterSingletonsInstantiated())
                .hasMessageContaining("没有加入 MybatisPlusInterceptor");
        assertThatThrownBy(
                        () -> verifier(harness.registry, null, fillingHandler()).afterSingletonsInstantiated())
                .hasMessageContaining("找不到 MybatisPlusInterceptor");
    }

    /** 拦截器注册在分页之后，启动失败。 */
    @Test
    void failsWhenRegisteredAfterPagination() {
        MybatisPlusInterceptor wrongOrder = new MybatisPlusInterceptor();
        wrongOrder.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        wrongOrder.addInnerInterceptor(harness.interceptor);
        assertThatThrownBy(() ->
                        verifier(harness.registry, wrongOrder, fillingHandler()).afterSingletonsInstantiated())
                .hasMessageContaining("必须注册在 PaginationInnerInterceptor 之前");
    }

    /** 清单里的表或列在库里不存在，启动失败。 */
    @Test
    void failsWhenTableOrColumnDoesNotExist() throws Exception {
        DataScopeRegistry missing = registry(
                """
                resources:
                  - { code: 'crm:ghost', name: 幽灵, table: crm_ghost, allowedScopes: [SELF] }
                  - { code: 'crm:customer', name: 客户, table: crm_customer, ownerDeptColumn: dept_missing, allowedScopes: [SELF] }
                """);
        assertThatThrownBy(() -> verifier(missing, harness.mybatisInterceptor, fillingHandler())
                        .afterSingletonsInstantiated())
                .hasMessageContaining("表不存在：crm_ghost")
                .hasMessageContaining("列不存在：crm_customer.dept_missing");
    }

    /** 没有任何 MetaObjectHandler 填充归属字段，启动失败。 */
    @Test
    void failsWhenNoHandlerFillsTheOwnerFields() {
        assertThatThrownBy(() -> verifier(harness.registry, harness.mybatisInterceptor, idleHandler())
                        .afterSingletonsInstantiated())
                .hasMessageContaining("没有被任何 MetaObjectHandler 填充");
        assertThatThrownBy(() -> verifier(harness.registry, harness.mybatisInterceptor, null)
                        .afterSingletonsInstantiated())
                .hasMessageContaining("没有被任何 MetaObjectHandler 填充");
    }

    /** 归属字段没有声明插入填充和禁止更新，启动失败。 */
    @Test
    void failsWhenOwnerFieldsAreNotAnnotated() throws Exception {
        DataScopeRegistry bad = registry(
                """
                resources:
                  - { code: 'crm:bad', name: 坏, table: crm_bad, allowedScopes: [SELF] }
                """);
        assertThatThrownBy(() -> verifier(bad, harness.mybatisInterceptor, fillingHandler())
                        .afterSingletonsInstantiated())
                .hasMessageContaining("fill = FieldFill.INSERT")
                .hasMessageContaining("FieldStrategy.NEVER");
    }
}
