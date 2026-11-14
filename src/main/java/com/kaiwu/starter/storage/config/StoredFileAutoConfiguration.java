package com.kaiwu.starter.storage.config;

import com.kaiwu.starter.storage.controller.StoredFileController;
import com.kaiwu.starter.storage.repository.JdbcStoredFileRepository;
import com.kaiwu.starter.storage.repository.StoredFileRepository;
import com.kaiwu.starter.storage.service.ObjectKeyPolicy;
import com.kaiwu.starter.storage.service.ObjectStorage;
import com.kaiwu.starter.storage.service.ObjectStorageResolver;
import com.kaiwu.starter.storage.service.StoredFilePurgeTask;
import com.kaiwu.starter.storage.service.StoredFileService;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 装配项目内统一文件记录（ADR 0041）。需要同时打开 {@code kaiwu.starter.storage.enabled} 与
 * {@code kaiwu.starter.storage.files.enabled}，并且项目有数据源、已建 {@code kaiwu_file} 表。
 */
@AutoConfiguration(
        after = ObjectStorageAutoConfiguration.class,
        afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@EnableConfigurationProperties(StoredFileProperties.class)
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "kaiwu.starter.storage.files", name = "enabled", havingValue = "true")
@ConditionalOnBean({DataSource.class, ObjectStorage.class})
public class StoredFileAutoConfiguration {

    /**
     * 创建基于 JDBC 的文件记录仓库。
     *
     * @param dataSource 业务项目的数据源
     * @param retentionEnabled 默认关闭的引用保护开关，启用前须完成新增迁移并停用旧删除实现
     * @return 仓库
     */
    @Bean
    @ConditionalOnMissingBean(StoredFileRepository.class)
    StoredFileRepository kaiwuStoredFileRepository(
            DataSource dataSource,
            @Value("${kaiwu.starter.storage.files.retention-enabled:false}") boolean retentionEnabled) {
        return new JdbcStoredFileRepository(new JdbcTemplate(dataSource), retentionEnabled);
    }

    /**
     * 创建文件服务。
     *
     * @param repository 文件记录仓库
     * @param storage 当前生效的存储
     * @param resolver 存储解析器
     * @param keyPolicy 本项目的 key 策略
     * @param storageProperties 对象存储配置，提供单文件大小上限
     * @param properties 文件配置，提供直传地址有效期
     * @return 文件服务
     */
    @Bean
    @ConditionalOnMissingBean(StoredFileService.class)
    StoredFileService kaiwuStoredFileService(
            StoredFileRepository repository,
            ObjectStorage storage,
            ObjectStorageResolver resolver,
            ObjectKeyPolicy keyPolicy,
            ObjectStorageProperties storageProperties,
            StoredFileProperties properties) {
        return new StoredFileService(
                repository,
                storage,
                resolver,
                keyPolicy,
                Clock.systemDefaultZone(),
                storageProperties.maxObjectSize().toBytes(),
                properties.uploadTtl());
    }

    /**
     * 创建「文件」管理页接口。
     *
     * @param service 文件服务
     * @param properties 文件配置
     * @return 控制器
     */
    @Bean
    @ConditionalOnMissingBean(StoredFileController.class)
    StoredFileController kaiwuStoredFileController(StoredFileService service, StoredFileProperties properties) {
        return new StoredFileController(service, properties.downloadTtl());
    }

    /**
     * 注册平台调度里的文件清理任务。
     *
     * @param service 文件服务
     * @param properties 文件配置
     * @return 清理任务处理器
     */
    @Bean
    @ConditionalOnMissingBean(StoredFilePurgeTask.class)
    StoredFilePurgeTask kaiwuStoredFilePurgeTask(StoredFileService service, StoredFileProperties properties) {
        return new StoredFilePurgeTask(service, properties.pendingTtl(), properties.deletedRetention());
    }
}
