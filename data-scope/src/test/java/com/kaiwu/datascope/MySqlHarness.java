package com.kaiwu.datascope;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.kaiwu.starter.KaiwuContext;
import com.kaiwu.starter.auth.model.ContextDataScope;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 真实 MySQL 上的测试环境：建表、装配 MyBatis-Plus 与数据范围拦截器、切换当前 Context。
 */
final class MySqlHarness implements AutoCloseable {

    static final String MANIFEST =
            """
            resources:
              - code: crm:customer
                name: 客户
                table: crm_customer
                allowedScopes: [ALL, DEPT_AND_CHILD, DEPT, SELF, CUSTOM_DEPT]
              - code: crm:contract
                name: 合同
                table: crm_contract
                allowedScopes: [ALL, DEPT, SELF]
            exempt:
              - { table: crm_industry, reason: 行业字典，全员共享 }
            """;

    private static final ThreadLocal<KaiwuContext> CONTEXT = new ThreadLocal<>();

    final MySQLContainer<?> mysql = new MySQLContainer<>(
                    DockerImageName.parse(System.getenv().getOrDefault("KAIWU_TEST_MYSQL_IMAGE", "mysql:8.4"))
                            .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("ds");
    DataSource dataSource;
    DataScopeRegistry registry;
    DataScopeInnerInterceptor interceptor;
    MybatisPlusInterceptor mybatisInterceptor;
    SqlSessionFactory sessions;
    final DataScopeOwnerFiller filler;
    MemberDirectory directory = userId -> new MemberDirectory.Placement(false, Set.of());

    /** 启动容器并完成全部装配。 */
    MySqlHarness() throws Exception {
        mysql.start();
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(mysql.getJdbcUrl());
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        dataSource = source;
        schema();
        registry = DataScopeRegistry.load(new ByteArrayInputStream(MANIFEST.getBytes(StandardCharsets.UTF_8)));
        interceptor = new DataScopeInnerInterceptor(registry);
        filler = new DataScopeOwnerFiller(registry);
        mybatisInterceptor = new MybatisPlusInterceptor();
        mybatisInterceptor.addInnerInterceptor(interceptor);
        mybatisInterceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setConfiguration(configuration);
        bean.setPlugins(mybatisInterceptor);
        GlobalConfig global = new GlobalConfig();
        global.setMetaObjectHandler(new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                filler.fill(metaObject);
            }

            @Override
            public void updateFill(MetaObject metaObject) {}
        });
        bean.setGlobalConfig(global);
        sessions = bean.getObject();
        sessions.getConfiguration().addMapper(Mappers.CustomerMapper.class);
        sessions.getConfiguration().addMapper(Mappers.ContractMapper.class);
        sessions.getConfiguration().addMapper(Mappers.IndustryMapper.class);
        install(userId -> directory.placement(userId));
    }

    /** 让 DataScopes 读取本测试环境的当前 Context 与成员查询。 */
    static void install(MemberDirectory members) {
        DataScopes.configure(CONTEXT::get, members);
    }

    private void schema() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (String table : new String[] {"crm_customer", "crm_contract"}) {
                statement.execute("DROP TABLE IF EXISTS " + table);
            }
            statement.execute("DROP TABLE IF EXISTS crm_industry");
            statement.execute(
                    """
                    CREATE TABLE crm_customer (
                      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      name VARCHAR(64) NOT NULL,
                      owner_user_id BIGINT NOT NULL,
                      owner_dept_id BIGINT NOT NULL,
                      UNIQUE KEY uk_name (name),
                      KEY idx_owner_user (owner_user_id),
                      KEY idx_owner_dept (owner_dept_id)
                    )""");
            statement.execute(
                    """
                    CREATE TABLE crm_contract (
                      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      customer_id BIGINT NOT NULL,
                      title VARCHAR(64) NOT NULL,
                      owner_user_id BIGINT NOT NULL,
                      owner_dept_id BIGINT NOT NULL,
                      KEY idx_owner_user (owner_user_id),
                      KEY idx_owner_dept (owner_dept_id)
                    )""");
            statement.execute("CREATE TABLE crm_industry (id BIGINT PRIMARY KEY, name VARCHAR(64) NOT NULL)");
            statement.execute("INSERT INTO crm_industry VALUES (1, 'IT'), (2, '制造')");
        }
    }

    /** 清空业务表。 */
    void reset() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM crm_contract");
            statement.execute("DELETE FROM crm_customer");
        }
    }

    /** 绕过拦截器，用 JDBC 直接写入夹具。 */
    long customer(String name, long user, long dept) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO crm_customer(name, owner_user_id, owner_dept_id) VALUES ('%s', %d, %d)"
                            .formatted(name, user, dept),
                    Statement.RETURN_GENERATED_KEYS);
            var keys = statement.getGeneratedKeys();
            keys.next();
            return keys.getLong(1);
        }
    }

    /** 绕过拦截器写入合同夹具。 */
    long contract(long customerId, String title, long user, long dept) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO crm_contract(customer_id, title, owner_user_id, owner_dept_id) VALUES (%d, '%s', %d, %d)"
                            .formatted(customer_id(customerId), title, user, dept),
                    Statement.RETURN_GENERATED_KEYS);
            var keys = statement.getGeneratedKeys();
            keys.next();
            return keys.getLong(1);
        }
    }

    private static long customer_id(long id) {
        return id;
    }

    /** 用 JDBC 查询单个数字，绕过拦截器。 */
    long scalar(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /** 在新会话（自动提交）里取得 Mapper。 */
    <M> M mapper(Class<M> type) {
        SqlSession session = sessions.openSession(true);
        return session.getMapper(type);
    }

    /**
     * 切换当前线程的 Context。
     *
     * @param user  用户 ID
     * @param dept  主部门，0 表示没有
     * @param rules 资源码到规则，可为空
     */
    static void as(long user, long dept, Map<String, ContextDataScope.Rule> rules) {
        ContextDataScope scope = new ContextDataScope(dept == 0 ? null : dept, rules, "test-fp");
        CONTEXT.set(context(user, scope));
    }

    /** 当前线程的 Context 不带 ds。 */
    static void asWithoutDs(long user) {
        CONTEXT.set(context(user, null));
    }

    /** 清除当前线程的 Context。 */
    static void asNobody() {
        CONTEXT.remove();
    }

    private static KaiwuContext context(long user, ContextDataScope scope) {
        return new KaiwuContext(
                Long.toString(user),
                "s",
                "1",
                "crm",
                Set.of(),
                Set.of(),
                "v",
                "aud",
                Instant.now(),
                Instant.now().plusSeconds(60),
                "t",
                scope);
    }

    /** 部门集合规则（隐含本人）。 */
    static ContextDataScope.Rule depts(Long... ids) {
        return new ContextDataScope.Rule(false, Set.of(ids));
    }

    /** 全部可见规则。 */
    static ContextDataScope.Rule all() {
        return new ContextDataScope.Rule(true, Set.of());
    }

    /** 关闭容器。 */
    @Override
    public void close() {
        asNobody();
        mysql.stop();
    }
}
