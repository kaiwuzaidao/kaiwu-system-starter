package com.kaiwu.datascope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 自动配置：关闭时不装配；接了模块却没注册拦截器时应用无法启动。 */
class DataScopeAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataScopeAutoConfiguration.class));

    /** kaiwu.data-scope.enabled=false 时不创建任何 Bean。 */
    @Test
    void disabledMeansNoBeans() {
        runner.withPropertyValues("kaiwu.data-scope.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(DataScopeRegistry.class));
    }

    /** 成员查询的装配条件把空串视同未配置：生成项目的占位配置不会让应用起不来。 */
    @Test
    void memberDirectoryConditionTreatsBlankAsUnconfigured() throws Exception {
        var method = DataScopeAutoConfiguration.class.getDeclaredMethod(
                "systemMemberDirectory",
                String.class,
                String.class,
                String.class,
                long.class,
                org.springframework.beans.factory.ObjectProvider.class);
        var condition =
                method.getAnnotation(org.springframework.boot.autoconfigure.condition.ConditionalOnExpression.class);
        assertThat(condition).isNotNull();
        var expression = new org.springframework.expression.spel.standard.SpelExpressionParser();
        for (String[] values : new String[][] {{"", ""}, {"http://x", ""}, {"", "zsch_1_x"}}) {
            String resolved = condition
                    .value()
                    .replace("${kaiwu.data-scope.members.system-address:}", values[0])
                    .replace("${kaiwu.data-scope.members.credential:}", values[1]);
            assertThat(expression.parseExpression(resolved).getValue(Boolean.class))
                    .isFalse();
        }
        String both = condition
                .value()
                .replace("${kaiwu.data-scope.members.system-address:}", "http://x")
                .replace("${kaiwu.data-scope.members.credential:}", "zsch_1_x");
        assertThat(expression.parseExpression(both).getValue(Boolean.class)).isTrue();
    }

    /** 引入模块但没有把拦截器加入 MybatisPlusInterceptor：启动自检让上下文起不来。 */
    @Test
    void contextFailsToStartWhenInterceptorIsNotRegistered() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure().getMessage() + causes(context.getStartupFailure()))
                    .contains("kaiwu-data-scope 启动自检失败");
        });
    }

    private static String causes(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            text.append(' ').append(t.getMessage());
        }
        return text.toString();
    }
}
