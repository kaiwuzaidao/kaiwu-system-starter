package com.kaiwu.starter.agent.typed;

import com.kaiwu.starter.KaiwuContext;
import com.kaiwu.starter.agent.AgentReadCapabilityHandler;
import com.kaiwu.starter.agent.protocol.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Builds the existing read protocol from explicit typed declarations. This is not an execution
 * endpoint: register the returned handler as a Bean and invoke it only through the Starter runtime.
 * The runtime still validates parameters, authenticates and authorizes every invocation. The bound
 * application service must retain resource ownership and business-state checks.
 */
public class TypedAgentRead<I, R> implements AgentReadCapabilityHandler {
    /** Business-owned identity and permission, never inferred from an HTTP route or DTO. */
    public record Identity(
            String projectCode, String code, String version, String description, String permission, DataScope scope) {}

    /** Typed access to fields declared by this capability; foreign field handles are rejected. */
    public static final class Arguments {
        private final List<AgentInput<?>> fields;
        private final Map<String, Object> values;

        private Arguments(List<AgentInput<?>> fields, Map<String, Object> values) {
            this.fields = fields;
            this.values = values;
        }

        public <T> T get(AgentInput<T> field) {
            if (!fields.contains(field)) throw new IllegalArgumentException("Undeclared input field");
            return field.decode(
                    values.containsKey(field.name())
                            ? values.get(field.name())
                            : field.spec().defaultValue());
        }
    }

    private final CapabilityManifest manifest;
    private final List<AgentInput<?>> inputs;
    private final List<AgentOutput<R>> outputs;
    private final Function<Arguments, I> inputFactory;
    private final BiFunction<I, KaiwuContext, List<R>> query;

    public TypedAgentRead(
            Identity identity,
            String inputTitle,
            List<AgentInput<?>> inputs,
            Function<Arguments, I> inputFactory,
            String outputTitle,
            List<AgentOutput<R>> outputs,
            OutputViewSpec.Pagination pagination,
            OutputViewSpec.Detail detail,
            int timeoutMillis,
            BiFunction<I, KaiwuContext, List<R>> query) {
        this.inputs = List.copyOf(inputs);
        this.outputs = List.copyOf(outputs);
        this.inputFactory = Objects.requireNonNull(inputFactory);
        this.query = Objects.requireNonNull(query);
        var params = new LinkedHashMap<String, ParamSpec>();
        for (var field : this.inputs) {
            if (params.putIfAbsent(field.name(), field.spec()) != null)
                throw new IllegalArgumentException("Duplicate input field");
        }
        var projection = new LinkedHashSet<String>();
        for (var field : this.outputs) {
            if (!projection.add(field.field().name())) throw new IllegalArgumentException("Duplicate output field");
        }
        var form = inputs.isEmpty()
                ? null
                : new InputFormSpec(
                        inputTitle, this.inputs.stream().map(AgentInput::field).toList());
        if (form != null) form.validate(params);
        var view = new OutputViewSpec(
                outputTitle, this.outputs.stream().map(AgentOutput::field).toList(), pagination, detail);
        view.validate(projection);
        view.validateNavigation(identity.projectCode(), params, form, true, Map.of());
        if (timeoutMillis < 100 || timeoutMillis > 10_000) throw new IllegalArgumentException("Invalid query timeout");
        this.manifest = new CapabilityManifest(
                identity.projectCode(),
                identity.code(),
                identity.version(),
                identity.description(),
                identity.permission(),
                identity.scope(),
                Map.copyOf(params),
                Set.copyOf(projection),
                Map.of(),
                true,
                true,
                ConfirmationMode.NONE,
                timeoutMillis,
                form,
                view);
    }

    @Override
    public final CapabilityManifest manifest() {
        return manifest;
    }

    @Override
    public final List<Map<String, Object>> query(Map<String, Object> parameters, KaiwuContext actor) {
        if (!manifest.params().keySet().containsAll(parameters.keySet()))
            throw new IllegalArgumentException("Undeclared input parameter");
        I input =
                inputFactory.apply(new Arguments(inputs, Collections.unmodifiableMap(new LinkedHashMap<>(parameters))));
        List<R> records = Objects.requireNonNull(query.apply(input, actor));
        if (records.size() > 100) throw new IllegalArgumentException("Query result exceeds runtime limit");
        var result = new ArrayList<Map<String, Object>>();
        for (R record : records) {
            Objects.requireNonNull(record);
            var row = new LinkedHashMap<String, Object>();
            for (var field : outputs) {
                Object value = field.value(record);
                if (value != null) row.put(field.field().name(), value);
            }
            result.add(Collections.unmodifiableMap(row));
        }
        return List.copyOf(result);
    }
}
