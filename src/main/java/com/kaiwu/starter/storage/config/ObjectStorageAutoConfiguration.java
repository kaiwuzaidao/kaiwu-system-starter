package com.kaiwu.starter.storage.config;

import com.kaiwu.starter.KaiwuStarterAutoConfiguration;
import com.kaiwu.starter.KaiwuStarterProperties;
import com.kaiwu.starter.client.ServiceAddressResolver;
import com.kaiwu.starter.storage.adapter.KaiwuObjectStorage;
import com.kaiwu.starter.storage.adapter.LocalDiskObjectStorage;
import com.kaiwu.starter.storage.client.StorageCredentialClient;
import com.kaiwu.starter.storage.exception.ObjectStorageException;
import com.kaiwu.starter.storage.service.ObjectKeyPolicy;
import com.kaiwu.starter.storage.service.ObjectStorage;
import com.kaiwu.starter.storage.service.ObjectStorageResolver;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配对象存储（ADR 0039）。只有 {@code kaiwu.starter.storage.enabled=true} 时生效；
 * 业务项目自行声明 {@link ObjectStorage} Bean 时以业务实现为准。配置不完整时启动失败，
 * 不静默降级，避免上线后才发现文件写到了意料之外的位置。
 */
@AutoConfiguration(after = KaiwuStarterAutoConfiguration.class)
@EnableConfigurationProperties(ObjectStorageProperties.class)
@ConditionalOnProperty(prefix = "kaiwu.starter.storage", name = "enabled", havingValue = "true")
public class ObjectStorageAutoConfiguration {

    private static final String PROVIDER = "provider";
    private static final String PREFIX = "kaiwu.starter.storage";

    /**
     * 创建绑定当前项目编码的 key 策略。
     *
     * @param starterProperties starter 基础配置，提供 {@code kaiwu.starter.project-code}
     * @return 本项目的 key 策略
     * @throws IllegalStateException 未启用 starter 或缺少项目编码时抛出
     */
    @Bean
    @ConditionalOnMissingBean(ObjectKeyPolicy.class)
    public ObjectKeyPolicy kaiwuObjectKeyPolicy(ObjectProvider<KaiwuStarterProperties> starterProperties) {
        KaiwuStarterProperties starter = starterProperties.getIfAvailable();
        if (starter == null) {
            throw new IllegalStateException("对象存储依赖 kaiwu.starter.enabled=true 与 kaiwu.starter.project-code");
        }
        return new ObjectKeyPolicy(starter.getProjectCode(), Clock.systemDefaultZone());
    }

    /**
     * 按存储引用找回存储。存储实现自身支持多绑定时直接使用；否则只认它自己的引用。
     *
     * @param storage 当前存储
     * @return 解析器
     */
    @Bean
    @ConditionalOnMissingBean(ObjectStorageResolver.class)
    public ObjectStorageResolver kaiwuObjectStorageResolver(ObjectStorage storage) {
        if (storage instanceof ObjectStorageResolver resolver) {
            return resolver;
        }
        return storageRef -> {
            if (storage.storageRef().equals(storageRef)) {
                return storage;
            }
            throw new ObjectStorageException("未知的存储引用：" + storageRef);
        };
    }

    /** provider=local：本机目录。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = PREFIX, name = PROVIDER, havingValue = "local", matchIfMissing = true)
    static class LocalConfiguration {

        /**
         * 创建本地磁盘存储。
         *
         * @param properties 对象存储配置
         * @param keyPolicy 本项目的 key 策略
         * @return 本地磁盘存储
         * @throws IllegalStateException 未配置根目录时抛出
         */
        @Bean
        @ConditionalOnMissingBean(ObjectStorage.class)
        ObjectStorage kaiwuObjectStorage(ObjectStorageProperties properties, ObjectKeyPolicy keyPolicy) {
            if (properties.local().rootDirectory() == null) {
                throw new IllegalStateException("本地对象存储需要配置 kaiwu.starter.storage.local.root-directory");
            }
            return new LocalDiskObjectStorage(
                    properties.local().rootDirectory(),
                    keyPolicy,
                    properties.maxObjectSize().toBytes());
        }
    }

    /** provider=kaiwu：向 System 申请临时凭证，经 S3 协议访问。要求业务项目引入 AWS SDK 的 s3 模块。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = PREFIX, name = PROVIDER, havingValue = "kaiwu")
    @ConditionalOnClass(name = "software.amazon.awssdk.services.s3.S3Client")
    static class KaiwuConfiguration {

        /**
         * 创建凭证客户端并预取一次凭证。
         *
         * @param properties 对象存储配置
         * @param starterProperties starter 基础配置
         * @param addressResolver 服务地址解析器
         * @return 凭证客户端
         */
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(StorageCredentialClient.class)
        StorageCredentialClient kaiwuStorageCredentialClient(
                ObjectStorageProperties properties,
                KaiwuStarterProperties starterProperties,
                ObjectProvider<ServiceAddressResolver> addressResolver) {
            ObjectStorageProperties.Kaiwu kaiwu = properties.kaiwu();
            StorageCredentialClient client = new StorageCredentialClient(
                    kaiwu.systemBaseUrl(),
                    kaiwu.credential(),
                    starterProperties.getProjectCode(),
                    kaiwu.checkInterval(),
                    kaiwu.timeout(),
                    addressResolver.getIfAvailable(ServiceAddressResolver::direct),
                    Clock.systemUTC());
            client.start();
            return client;
        }

        /**
         * 创建 S3 协议存储入口。
         *
         * @param properties 对象存储配置
         * @param credentials 凭证客户端
         * @param keyPolicy 本项目的 key 策略
         * @return 存储入口，同时也是存储引用解析器
         */
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(ObjectStorage.class)
        KaiwuObjectStorage kaiwuObjectStorage(
                ObjectStorageProperties properties, StorageCredentialClient credentials, ObjectKeyPolicy keyPolicy) {
            return new KaiwuObjectStorage(
                    credentials,
                    keyPolicy,
                    properties.maxObjectSize().toBytes(),
                    properties.kaiwu().storageName(),
                    Clock.systemUTC());
        }
    }

    /** provider=kaiwu 但缺少 AWS SDK：启动失败并说明需要引入的依赖。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = PREFIX, name = PROVIDER, havingValue = "kaiwu")
    @ConditionalOnMissingClass("software.amazon.awssdk.services.s3.S3Client")
    static class MissingSdkConfiguration {

        /**
         * 构造时即失败，让配置错误在启动阶段暴露。
         *
         * @throws IllegalStateException 总是抛出
         */
        MissingSdkConfiguration() {
            throw new IllegalStateException(
                    "provider=kaiwu 需要引入 software.amazon.awssdk:s3 与 url-connection-client，见 docs/OBJECT_STORAGE.md");
        }
    }
}
