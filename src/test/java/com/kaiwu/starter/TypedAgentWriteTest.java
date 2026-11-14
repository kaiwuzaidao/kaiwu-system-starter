package com.kaiwu.starter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kaiwu.starter.agent.*;
import com.kaiwu.starter.agent.protocol.*;
import com.kaiwu.starter.agent.typed.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TypedAgentWriteTest {
    private static final AgentInput<String> NAME = AgentInput.text("name", "名称", true, 30, false);
    private static final AgentInput<Integer> DAYS = AgentInput.integer("days", "天数", false, 1, 30, 7);

    record Input(String name, Integer days) {}

    record Result(String id, String name, String secret) {}

    private final AtomicInteger writes = new AtomicInteger();
    private final KaiwuContext actor = mock(KaiwuContext.class);
    private final AgentInputSchema<Input> schema =
            new AgentInputSchema<>("修改配置", List.of(NAME, DAYS), args -> new Input(args.get(NAME), args.get(DAYS)));

    private TypedAgentWrite<Input, Result> handler() {
        return new TypedAgentWrite<>(
                identity(),
                schema,
                List.of(AgentOutput.text("id", "编号", Result::id), AgentOutput.text("name", "名称", Result::name)),
                ConfirmationMode.REQUIRED,
                3000,
                (input, received) -> {
                    assertThat(received).isSameAs(actor);
                    assertThat(input.days()).isEqualTo(7);
                    return new AgentWritePreview(
                            Map.of("name", input.name()), Map.of("name", "名称"), 1, "{\"revision\":4}");
                },
                (input, preconditions, received) -> {
                    assertThat(received).isSameAs(actor);
                    if (!"{\"revision\":4}".equals(preconditions)) throw new IllegalStateException("conflict");
                    writes.incrementAndGet();
                    return new Result("9000000000000000001", input.name(), "must-not-leak");
                });
    }

    private TypedAgentWrite.Identity identity() {
        return new TypedAgentWrite.Identity(
                "demo", "demo.config.update", "1", "修改配置名称", "config:config:update", DataScope.MEMBER_PROJECTS);
    }

    @Test
    void previewDoesNotExecuteAndMutationRetainsFrozenPreconditionsAndSafeProjection() {
        var handler = handler();
        var input = AgentReadRuntime.validate(handler.manifest(), Map.of("name", "new"));
        assertThat(handler.preview(input, actor).displayLabels()).containsEntry("name", "名称");
        assertThat(writes).hasValue(0);
        assertThatThrownBy(() -> handler.execute(input, "{\"revision\":3}", actor))
                .hasMessage("conflict");
        assertThat(writes).hasValue(0);
        assertThat(AgentExecutionCodec.parameters(handler.execute(input, "{\"revision\":4}", actor)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("id", "9000000000000000001", "name", "new"));
        assertThat(writes).hasValue(1);
    }

    @Test
    void generatedManifestMatchesExplicitDeclaration() {
        var expected = new CapabilityManifest(
                "demo",
                "demo.config.update",
                "1",
                "修改配置名称",
                "config:config:update",
                DataScope.MEMBER_PROJECTS,
                Map.of(
                        "name", new ParamSpec(ParamType.STRING, true, null, null, 30, null, null, null),
                        "days", new ParamSpec(ParamType.INT, false, 1, 30, null, null, null, 7)),
                Set.of("id", "name"),
                Map.of(),
                false,
                true,
                ConfirmationMode.REQUIRED,
                3000,
                new InputFormSpec(
                        "修改配置",
                        List.of(
                                new InputFormSpec.Field("name", "名称", false, null),
                                new InputFormSpec.Field("days", "天数", false, null))));
        assertThat(AgentExecutionCodec.manifestHash(handler().manifest()))
                .isEqualTo(AgentExecutionCodec.manifestHash(expected));
    }

    @Test
    void existingValidationRejectsMissingAndOutOfRangeInputsAndUnknownIdentityNeverBinds() {
        var h = handler();
        assertThatThrownBy(() -> AgentReadRuntime.validate(h.manifest(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentReadRuntime.validate(h.manifest(), Map.of("name", "ok", "days", 31)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> h.preview(Map.of("name", "ok", "userId", "42"), actor))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> h.execute(Map.of("name", "ok", "userId", "42"), "{}", actor))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(writes).hasValue(0);
    }

    @Test
    void refusesUnconfirmedWritesDuplicateFieldsAndForeignInputHandles() {
        assertThatThrownBy(() -> new TypedAgentWrite<>(
                        identity(),
                        schema,
                        List.of(AgentOutput.text("id", "编号", Result::id)),
                        ConfirmationMode.NONE,
                        3000,
                        (input, ctx) -> null,
                        (input, pre, ctx) -> null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentInputSchema<>("配置", List.of(NAME, NAME), args -> args.get(NAME)))
                .isInstanceOf(IllegalArgumentException.class);
        var foreign = AgentInput.text("name", "名称", true, 30, false);
        var h = new TypedAgentWrite<>(
                identity(),
                new AgentInputSchema<>("配置", List.of(NAME), args -> args.get(foreign)),
                List.of(AgentOutput.text("id", "编号", Result::id)),
                ConfirmationMode.STRONG,
                3000,
                (input, ctx) -> null,
                (input, pre, ctx) -> null);
        assertThatThrownBy(() -> h.preview(Map.of("name", "x"), actor)).hasMessage("Undeclared input field");
    }

    @Test
    void businessDenialPropagatesInBothPhases() {
        var h = new TypedAgentWrite<>(
                identity(),
                schema,
                List.of(AgentOutput.text("id", "编号", Result::id)),
                ConfirmationMode.REQUIRED,
                3000,
                (input, ctx) -> {
                    throw new SecurityException("denied");
                },
                (input, pre, ctx) -> {
                    throw new SecurityException("denied");
                });
        assertThatThrownBy(() -> h.preview(Map.of("name", "x"), actor)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> h.execute(Map.of("name", "x"), "{}", actor)).isInstanceOf(SecurityException.class);
    }
}
