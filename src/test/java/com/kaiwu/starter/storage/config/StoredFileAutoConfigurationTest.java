package com.kaiwu.starter.storage.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaiwu.starter.KaiwuStarterProperties;
import com.kaiwu.starter.storage.controller.StoredFileController;
import com.kaiwu.starter.storage.repository.JdbcStoredFileRepository;
import com.kaiwu.starter.storage.service.StoredFilePurgeTask;
import com.kaiwu.starter.storage.service.StoredFileService;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 验证统一文件记录默认关闭，开启后在有数据源与存储时装配全部组件。 */
class StoredFileAutoConfigurationTest {

    @TempDir
    Path root;

    /**
     * 创建带项目编码、本地存储的运行器。
     *
     * @return 上下文运行器
     */
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(ObjectStorageAutoConfiguration.class, StoredFileAutoConfiguration.class))
                .withBean(KaiwuStarterProperties.class, () -> {
                    KaiwuStarterProperties properties = new KaiwuStarterProperties();
                    properties.setProjectCode("demo");
                    return properties;
                })
                .withPropertyValues(
                        "kaiwu.starter.storage.enabled=true", "kaiwu.starter.storage.local.root-directory=" + root);
    }

    /** 不显式开启时不装配任何文件组件，既有项目升级 starter 后行为不变。 */
    @Test
    void disabledByDefault() {
        runner().withBean(DataSource.class, () -> new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/none"))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(StoredFileService.class));
    }

    /** 开启且具备数据源与存储时，仓库、服务、控制器与清理任务一并装配。 */
    @Test
    void enabledWiresRepositoryServiceControllerAndPurgeTask() {
        runner().withPropertyValues("kaiwu.starter.storage.files.enabled=true")
                .withBean(DataSource.class, () -> new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/none"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JdbcStoredFileRepository.class);
                    assertThat(context).hasSingleBean(StoredFileService.class);
                    assertThat(context).hasSingleBean(StoredFileController.class);
                    assertThat(context.getBean(StoredFilePurgeTask.class).taskType())
                            .isEqualTo("kaiwu.file.purge");
                });
    }

    /** 文件功能启用也不会默认开启引用协议；无需引用表即可保持旧单服务配置。 */
    @Test
    void retentionRemainsOffUnlessExplicitlyEnabled() {
        runner().withPropertyValues("kaiwu.starter.storage.files.enabled=true")
                .withBean(DataSource.class, () -> new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/none"))
                .run(context -> {
                    var repository = context.getBean(JdbcStoredFileRepository.class);
                    assertThatThrownBy(() -> repository.retain("id", "workflow", "attempt"))
                            .isInstanceOf(UnsupportedOperationException.class);
                    assertThatThrownBy(() -> repository.release("id", "workflow", "attempt"))
                            .isInstanceOf(UnsupportedOperationException.class);
                    assertThatThrownBy(() -> repository.claimEvidenceUpload("id"))
                            .isInstanceOf(UnsupportedOperationException.class);
                });
    }

    /** 没有数据源时即使开启也不装配，避免业务项目无库时启动失败。 */
    @Test
    void enabledWithoutDataSourceStaysOff() {
        runner().withPropertyValues("kaiwu.starter.storage.files.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(StoredFileService.class));
    }
}
