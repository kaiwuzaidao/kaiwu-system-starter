package com.kaiwu.datascope;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.kaiwu.starter.StarterContext;
import com.kaiwu.starter.client.ServiceAddressResolver;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import javax.sql.DataSource;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;

/**
 * 数据范围模块的自动配置：classpath 上有 MyBatis-Plus 且 {@code kaiwu.data-scope.enabled} 不为 false 时生效。
 *
 * <p>清单缺失即启动失败——接了模块却没有 {@code data-scopes.yml} 等于没有保护。拦截器本身需要业务项目
 * 在 {@code MybatisPlusInterceptor} 里加入（必须在分页之前），启动自检会验证这一点。</p>
 */
@AutoConfiguration
@ConditionalOnClass(MybatisPlusInterceptor.class)
@ConditionalOnProperty(prefix = "kaiwu.data-scope", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DataScopeAutoConfiguration {

    /** 读取 classpath 上的受控资源清单。 */
    @Bean
    @ConditionalOnMissingBean
    public DataScopeRegistry dataScopeRegistry() throws IOException {
        ClassPathResource resource = new ClassPathResource(DataScopeRegistry.LOCATION);
        if (!resource.exists()) {
            throw new IllegalStateException("缺少 " + DataScopeRegistry.LOCATION
                    + "：引入 kaiwu-data-scope 就必须提供清单；确实不需要请设置 kaiwu.data-scope.enabled=false");
        }
        try (InputStream input = resource.getInputStream()) {
            return DataScopeRegistry.load(input);
        }
    }

    /** 数据范围拦截器；业务项目把它加入 MybatisPlusInterceptor。 */
    @Bean
    @ConditionalOnMissingBean
    public DataScopeInnerInterceptor dataScopeInnerInterceptor(DataScopeRegistry registry) {
        return new DataScopeInnerInterceptor(registry);
    }

    /** 归属列填充器；项目已有 MetaObjectHandler 时在其中委托调用。 */
    @Bean
    @ConditionalOnMissingBean
    public DataScopeOwnerFiller dataScopeOwnerFiller(DataScopeRegistry registry) {
        return new DataScopeOwnerFiller(registry);
    }

    /** 项目没有自己的 MetaObjectHandler 时，提供一个只负责归属列的默认实现。 */
    @Bean
    @ConditionalOnMissingBean(MetaObjectHandler.class)
    public MetaObjectHandler dataScopeMetaObjectHandler(DataScopeOwnerFiller filler) {
        return new MetaObjectHandler() {
            /** 插入时填归属列。 */
            @Override
            public void insertFill(MetaObject metaObject) {
                filler.fill(metaObject);
            }

            /** 归属列不随更新变化。 */
            @Override
            public void updateFill(MetaObject metaObject) {}
        };
    }

    /**
     * 默认成员查询：System 地址和服务凭据都非空时才创建（空串视同未配置，生成项目的占位配置不会让应用起不来），业务侧自己提供 {@link MemberDirectory} 时让位。
     * 项目编码复用 {@code kaiwu.starter.project-code}。
     */
    @Bean
    @ConditionalOnMissingBean(MemberDirectory.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
            "!'${kaiwu.data-scope.members.system-address:}'.isBlank() and !'${kaiwu.data-scope.members.credential:}'.isBlank()")
    public MemberDirectory systemMemberDirectory(
            @Value("${kaiwu.data-scope.members.system-address}") String systemAddress,
            @Value("${kaiwu.data-scope.members.credential}") String credential,
            @Value("${kaiwu.starter.project-code:}") String projectCode,
            @Value("${kaiwu.data-scope.members.timeout-seconds:3}") long timeoutSeconds,
            ObjectProvider<ServiceAddressResolver> resolver) {
        return new SystemMemberDirectory(
                systemAddress,
                projectCode,
                credential,
                resolver.getIfAvailable(ServiceAddressResolver::direct),
                Duration.ofSeconds(timeoutSeconds));
    }

    /** 把成员查询和 Context 来源交给静态入口 {@link DataScopes}。 */
    @Bean
    public org.springframework.beans.factory.InitializingBean dataScopesRuntime(
            ObjectProvider<MemberDirectory> directory) {
        return () -> DataScopes.configure(StarterContext::current, directory.getIfAvailable());
    }

    /** 启动自检。 */
    @Bean
    public DataScopeStartupVerifier dataScopeStartupVerifier(
            DataScopeRegistry registry,
            DataScopeInnerInterceptor interceptor,
            ObjectProvider<MybatisPlusInterceptor> mybatisInterceptors,
            ObjectProvider<MetaObjectHandler> handlers,
            ObjectProvider<DataSource> dataSource) {
        return new DataScopeStartupVerifier(registry, interceptor, mybatisInterceptors, handlers, dataSource);
    }
}
