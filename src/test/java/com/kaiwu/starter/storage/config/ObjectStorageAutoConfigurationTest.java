package com.kaiwu.starter.storage.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.kaiwu.starter.KaiwuStarterProperties;
import com.kaiwu.starter.storage.adapter.KaiwuObjectStorage;
import com.kaiwu.starter.storage.adapter.LocalDiskObjectStorage;
import com.kaiwu.starter.storage.exception.ObjectStorageException;
import com.kaiwu.starter.storage.service.ObjectStorage;
import com.kaiwu.starter.storage.service.ObjectStorageResolver;
import com.kaiwu.starter.storage.support.FakeStorageSystem;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 验证对象存储默认关闭、配置不全时启动失败，以及业务自定义实现优先。 */
class ObjectStorageAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObjectStorageAutoConfiguration.class))
            .withBean(KaiwuStarterProperties.class, () -> {
                KaiwuStarterProperties properties = new KaiwuStarterProperties();
                properties.setProjectCode("demo");
                return properties;
            });

    @TempDir
    Path root;

    /** 未显式开启时不装配任何存储 Bean。 */
    @Test
    void disabledByDefault() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ObjectStorage.class));
    }

    /** 开启本地实现并给出目录时装配本地存储，大小上限按配置生效。 */
    @Test
    void enabledLocalStorageStarts() {
        runner.withPropertyValues(
                        "kaiwu.starter.storage.enabled=true",
                        "kaiwu.starter.storage.local.root-directory=" + root,
                        "kaiwu.starter.storage.max-object-size=1KB")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ObjectStorage.class);
                    assertThat(context.getBean(ObjectStorage.class)).isInstanceOf(LocalDiskObjectStorage.class);
                });
    }

    /** 开启本地实现却没有目录属于配置错误，必须启动失败而不是写到工作目录。 */
    @Test
    void localStorageWithoutRootDirectoryFailsFast() {
        runner.withPropertyValues("kaiwu.starter.storage.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    /** 缺少项目编码时无法确定前缀，必须启动失败。 */
    @Test
    void missingProjectCodeFailsFast() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ObjectStorageAutoConfiguration.class))
                .withPropertyValues(
                        "kaiwu.starter.storage.enabled=true", "kaiwu.starter.storage.local.root-directory=" + root)
                .run(context -> assertThat(context).hasFailed());
    }

    /** provider=kaiwu 缺少 System 地址或凭据时启动失败。 */
    @Test
    void kaiwuProviderWithoutSystemAddressFailsFast() {
        runner.withPropertyValues("kaiwu.starter.storage.enabled=true", "kaiwu.starter.storage.provider=kaiwu")
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * provider=kaiwu 配置完整时装配 S3 入口，且它同时承担存储引用解析。
     *
     * @throws Exception 模拟 System 启动失败时抛出
     */
    @Test
    void kaiwuProviderStartsAndResolvesReferences() throws Exception {
        try (FakeStorageSystem system = new FakeStorageSystem()) {
            system.respond(200, FakeStorageSystem.bundle(""));
            runner.withPropertyValues(
                            "kaiwu.starter.storage.enabled=true",
                            "kaiwu.starter.storage.provider=kaiwu",
                            "kaiwu.starter.storage.kaiwu.system-base-url=" + system.baseUrl(),
                            "kaiwu.starter.storage.kaiwu.credential=" + FakeStorageSystem.TOKEN)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(ObjectStorage.class)).isInstanceOf(KaiwuObjectStorage.class);
                        assertThat(context.getBean(ObjectStorageResolver.class))
                                .isSameAs(context.getBean(ObjectStorage.class));
                    });
            assertThat(system.calls()).isPositive();
        }
    }

    /** 本地存储也提供解析器，只认自己的引用。 */
    @Test
    void localStorageResolvesItsOwnReference() {
        runner.withPropertyValues(
                        "kaiwu.starter.storage.enabled=true", "kaiwu.starter.storage.local.root-directory=" + root)
                .run(context -> {
                    ObjectStorageResolver resolver = context.getBean(ObjectStorageResolver.class);
                    assertThat(resolver.forRef("local")).isSameAs(context.getBean(ObjectStorage.class));
                    assertThatThrownBy(() -> resolver.forRef("other")).isInstanceOf(ObjectStorageException.class);
                });
    }

    /** 业务项目自行声明的实现优先于 starter 默认实现。 */
    @Test
    void businessProvidedStorageWins() {
        ObjectStorage custom = mock(ObjectStorage.class);
        runner.withPropertyValues("kaiwu.starter.storage.enabled=true")
                .withBean(ObjectStorage.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ObjectStorage.class)).isSameAs(custom);
                });
    }
}
