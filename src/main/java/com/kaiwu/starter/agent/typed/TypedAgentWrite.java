package com.kaiwu.starter.agent.typed;

import com.kaiwu.starter.KaiwuContext;
import com.kaiwu.starter.agent.*;
import com.kaiwu.starter.agent.protocol.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiFunction;

/** Typed adapter for the existing confirmed, transactional write runtime; never an execution endpoint. */
public class TypedAgentWrite<I, R> implements AgentWriteCapabilityHandler {
    public record Identity(
            String projectCode, String code, String version, String description, String permission, DataScope scope) {}

    @FunctionalInterface
    public interface Mutation<I, R> {
        /** Must recheck ownership and frozen resource versions in the ledger's database transaction. */
        R execute(I input, String preconditionsJson, KaiwuContext actor);
    }

    private final CapabilityManifest manifest;
    private final AgentInputSchema<I> inputs;
    private final List<AgentOutput<R>> outputs;
    private final BiFunction<I, KaiwuContext, AgentWritePreview> preview;
    private final Mutation<I, R> mutation;

    public TypedAgentWrite(
            Identity identity,
            AgentInputSchema<I> inputs,
            List<AgentOutput<R>> outputs,
            ConfirmationMode confirmation,
            int timeoutMillis,
            BiFunction<I, KaiwuContext, AgentWritePreview> preview,
            Mutation<I, R> mutation) {
        Objects.requireNonNull(identity);
        this.inputs = Objects.requireNonNull(inputs);
        this.outputs = List.copyOf(outputs);
        this.preview = Objects.requireNonNull(preview);
        this.mutation = Objects.requireNonNull(mutation);
        if (confirmation != ConfirmationMode.REQUIRED && confirmation != ConfirmationMode.STRONG)
            throw new IllegalArgumentException("Writes require confirmation");
        if (timeoutMillis < 100 || timeoutMillis > 10_000) throw new IllegalArgumentException("Invalid write timeout");
        var names = new LinkedHashSet<String>();
        for (var output : this.outputs) {
            if (!names.add(output.field().name())) throw new IllegalArgumentException("Duplicate output field");
        }
        // Validate declaration metadata without adding a new result-view wire field to old write manifests.
        new OutputViewSpec("操作结果", this.outputs.stream().map(AgentOutput::field).toList()).validate(names);
        if (names.isEmpty()) throw new IllegalArgumentException("Write result requires safe output fields");
        manifest = new CapabilityManifest(
                identity.projectCode(),
                identity.code(),
                identity.version(),
                identity.description(),
                identity.permission(),
                identity.scope(),
                inputs.params(),
                names,
                Map.of(),
                false,
                true,
                confirmation,
                timeoutMillis,
                inputs.form());
    }

    @Override
    public final CapabilityManifest manifest() {
        return manifest;
    }

    @Override
    public final AgentWritePreview preview(Map<String, Object> parameters, KaiwuContext actor) {
        return Objects.requireNonNull(preview.apply(inputs.bind(parameters), actor));
    }

    @Override
    public final String execute(Map<String, Object> parameters, String preconditionsJson, KaiwuContext actor) {
        R result = Objects.requireNonNull(mutation.execute(inputs.bind(parameters), preconditionsJson, actor));
        var safe = new LinkedHashMap<String, Object>();
        for (var output : outputs) {
            Object value = output.value(result);
            if (value != null) safe.put(output.field().name(), value);
        }
        return new String(AgentExecutionCodec.encode(safe), StandardCharsets.UTF_8);
    }
}
