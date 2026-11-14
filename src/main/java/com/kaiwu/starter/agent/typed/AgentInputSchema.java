package com.kaiwu.starter.agent.typed;

import com.kaiwu.starter.agent.protocol.InputFormSpec;
import com.kaiwu.starter.agent.protocol.ParamSpec;
import java.util.*;
import java.util.function.Function;

/** Shared input declaration for a form, wire parameters and a business DTO. */
public final class AgentInputSchema<I> {
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

    private final List<AgentInput<?>> fields;
    private final Map<String, ParamSpec> params;
    private final InputFormSpec form;
    private final Function<Arguments, I> factory;

    public AgentInputSchema(String title, List<AgentInput<?>> fields, Function<Arguments, I> factory) {
        this.fields = List.copyOf(fields);
        this.factory = Objects.requireNonNull(factory);
        if (this.fields.size() > 32) throw new IllegalArgumentException("Too many input fields");
        var parameters = new LinkedHashMap<String, ParamSpec>();
        for (var field : this.fields) {
            if (parameters.putIfAbsent(field.name(), field.spec()) != null)
                throw new IllegalArgumentException("Duplicate input field");
        }
        params = Map.copyOf(parameters);
        form = this.fields.isEmpty()
                ? null
                : new InputFormSpec(
                        title, this.fields.stream().map(AgentInput::field).toList());
        if (form != null) form.validate(params);
    }

    public Map<String, ParamSpec> params() {
        return params;
    }

    public InputFormSpec form() {
        return form;
    }

    /** Called only after the existing execution runtime validates types, bounds and required values. */
    I bind(Map<String, Object> values) {
        if (!params.keySet().containsAll(values.keySet()))
            throw new IllegalArgumentException("Undeclared input parameter");
        return Objects.requireNonNull(
                factory.apply(new Arguments(fields, Collections.unmodifiableMap(new LinkedHashMap<>(values)))));
    }
}
