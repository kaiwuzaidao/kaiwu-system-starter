package com.kaiwu.starter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kaiwu.starter.agent.AgentExecutionCodec;
import com.kaiwu.starter.agent.protocol.*;
import com.kaiwu.starter.agent.typed.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class TypedAgentReadTest {
    private static final AgentInput<Integer> PAGE = AgentInput.integer("page", "页码", false, 1, 1000, 1);
    private static final AgentInput<Integer> SIZE = AgentInput.integer("pageSize", "每页条数", false, 1, 20, 10);

    record Query(Integer page, Integer size) {}

    record Row(String id, Integer enabled, Integer popupEnabled, Integer trialDays, String secret) {}

    private TypedAgentRead<Query, Row> definition(java.util.function.BiFunction<Query, KaiwuContext, List<Row>> query) {
        return new TypedAgentRead<>(
                new TypedAgentRead.Identity(
                        "crm",
                        "crm.trial-config.list",
                        "2",
                        "查询客户客户端试用活动配置及启用状态",
                        "trialconfig:trialconfig:list",
                        DataScope.MEMBER_PROJECTS),
                "查询试用配置",
                List.of(PAGE, SIZE),
                args -> new Query(args.get(PAGE), args.get(SIZE)),
                "试用配置列表",
                List.of(
                        AgentOutput.text("id", "配置编号", Row::id),
                        AgentOutput.enabled("enabled", "是否启用", Row::enabled),
                        AgentOutput.enabled("popupEnabled", "是否弹窗", Row::popupEnabled),
                        AgentOutput.number("trialDays", "试用天数", Row::trialDays)),
                new OutputViewSpec.Pagination("page", "pageSize"),
                new OutputViewSpec.Detail("查看详情", "crm.trial-config.detail", "configId", "id"),
                3000,
                query);
    }

    @Test
    void generatedManifestPreservesApprovedTrialListDigest() {
        var handler = definition((input, actor) -> List.of());
        assertThat(AgentExecutionCodec.manifestHash(handler.manifest()))
                .isEqualTo("2ef01408edbfae7adcd0ba96f80fc11fd7b2f71f3a4ccd03173cbf4eeaf27f1c");
    }

    @Test
    void bindsDtoDefaultsActorAndExplicitProjectionWithoutLeakingEntityFields() {
        KaiwuContext actor = mock(KaiwuContext.class);
        var handler = definition((input, received) -> {
            assertThat(input).isEqualTo(new Query(1, 10));
            assertThat(received).isSameAs(actor);
            return List.of(new Row("9000000000000000001", 1, null, 7, "must-not-escape"));
        });
        var result = handler.query(AgentReadRuntime.validate(handler.manifest(), Map.of()), actor);
        assertThat(result).containsExactly(Map.of("id", "9000000000000000001", "enabled", 1, "trialDays", 7));
        assertThatThrownBy(() -> result.getFirst().put("secret", "x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void typedStringListBindsValidatedValuesWithoutCommaParsing() {
        var ids = AgentInput.stringList("ids", "菜单编号", true, 1, 3, 19);
        var handler = new TypedAgentRead<List<String>, Row>(
                new TypedAgentRead.Identity("demo", "demo.menu.lookup", "1", "查询菜单", "demo:menu:list", DataScope.ALL),
                "选择菜单",
                List.of(ids),
                args -> args.get(ids),
                "菜单",
                List.of(AgentOutput.text("id", "编号", Row::id)),
                null,
                null,
                3000,
                (input, actor) -> {
                    assertThat(input).containsExactly("11", "12");
                    return List.of();
                });
        handler.query(
                AgentReadRuntime.validate(handler.manifest(), Map.of("ids", List.of("11", "12"))),
                mock(KaiwuContext.class));
        assertThatThrownBy(() -> AgentReadRuntime.validate(handler.manifest(), Map.of("ids", "11,12")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void existingRuntimeRejectsUnboundedAndUnknownInputBeforeBinding() {
        var handler = definition((input, actor) -> {
            throw new AssertionError("must not execute");
        });
        assertThatThrownBy(() -> AgentReadRuntime.validate(handler.manifest(), Map.of("pageSize", 21)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentReadRuntime.validate(handler.manifest(), Map.of("page", "1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.query(Map.of("userId", "1"), mock(KaiwuContext.class)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retainsResourceAuthorizationFailureFromBusinessService() {
        var handler = definition((input, actor) -> {
            throw new IllegalStateException("denied");
        });
        assertThatThrownBy(() -> handler.query(Map.of(), mock(KaiwuContext.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("denied");
    }

    @Test
    void fieldHandlesCannotBeSubstitutedBySameNameFromAnotherDefinition() {
        var foreign = AgentInput.integer("page", "页码", false, 1, 1000, 1);
        var handler = new TypedAgentRead<Integer, Row>(
                new TypedAgentRead.Identity(
                        "demo", "demo.record.list", "1", "查询记录", "record:record:list", DataScope.SELF),
                "查询",
                List.of(PAGE),
                args -> args.get(foreign),
                "记录",
                List.of(AgentOutput.text("id", "编号", Row::id)),
                null,
                null,
                3000,
                (input, actor) -> List.of());
        assertThatThrownBy(() -> handler.query(Map.of(), mock(KaiwuContext.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Undeclared input field");
    }

    @Test
    void rejectsDuplicateOrInvalidDeclarations() {
        assertThatThrownBy(() -> AgentInput.integer("page", "页码", false, 1, 20, 21))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TypedAgentRead<Query, Row>(
                        new TypedAgentRead.Identity(
                                "demo", "demo.record.list", "1", "查询记录", "record:record:list", DataScope.SELF),
                        "查询",
                        List.of(PAGE, PAGE),
                        args -> new Query(1, 10),
                        "记录",
                        List.of(AgentOutput.text("id", "编号", Row::id)),
                        null,
                        null,
                        3000,
                        (input, actor) -> List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supportsEnumAndDateTimeWithoutChangingWireTypes() {
        var status = AgentInput.enumeration("status", "状态", false, Set.of("OPEN", "CLOSED"), "demo.status");
        var time = AgentInput.dateTime("time", "时间", false);
        record Filters(String status, LocalDateTime time) {}
        var handler = new TypedAgentRead<Filters, Filters>(
                new TypedAgentRead.Identity(
                        "demo", "demo.record.list", "1", "查询记录", "record:record:list", DataScope.SELF),
                "查询",
                List.of(status, time),
                args -> new Filters(args.get(status), args.get(time)),
                "记录",
                List.of(
                        AgentOutput.dictionary("status", "状态", "demo.status", Filters::status),
                        AgentOutput.dateTime("time", "时间", Filters::time)),
                null,
                null,
                3000,
                (input, actor) -> List.of(input));
        var input = Map.<String, Object>of("status", "OPEN", "time", "2026-09-28T10:00:00");
        assertThat(handler.query(AgentReadRuntime.validate(handler.manifest(), input), mock(KaiwuContext.class)))
                .containsExactly(input);
        assertThatThrownBy(() -> AgentReadRuntime.validate(handler.manifest(), Map.of("status", "UNKNOWN")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
