package com.kaiwu.starter.agent.typed;

import com.kaiwu.starter.agent.protocol.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** One explicit parameter definition shared by schema, form and typed DTO binding. */
public final class AgentInput<T> {
    private final String name;
    private final ParamSpec spec;
    private final InputFormSpec.Field field;
    private final Function<Object, T> decoder;

    private AgentInput(
            String name,
            String label,
            ParamSpec spec,
            String dictionary,
            boolean multiline,
            Function<Object, T> decoder) {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))
            throw new IllegalArgumentException("Invalid input name");
        this.name = name;
        this.spec = spec;
        this.field = new InputFormSpec.Field(name, label, multiline, dictionary);
        this.decoder = decoder;
    }

    public static AgentInput<String> text(
            String name, String label, boolean required, int maxLength, boolean multiline) {
        if (maxLength < 1 || maxLength > 4096) throw new IllegalArgumentException("Invalid text bound");
        return new AgentInput<>(
                name,
                label,
                new ParamSpec(ParamType.STRING, required, null, null, maxLength, null, null, null),
                null,
                multiline,
                String.class::cast);
    }

    public static AgentInput<Integer> integer(
            String name, String label, boolean required, int min, int max, Integer defaultValue) {
        if (min > max || (defaultValue != null && (defaultValue < min || defaultValue > max)))
            throw new IllegalArgumentException("Invalid integer bounds/default");
        return new AgentInput<>(
                name,
                label,
                new ParamSpec(ParamType.INT, required, min, max, null, null, null, defaultValue),
                null,
                false,
                value -> {
                    if (!(value instanceof Number)) throw new IllegalArgumentException("Expected integer");
                    return new BigDecimal(value.toString()).intValueExact();
                });
    }

    public static AgentInput<Boolean> bool(String name, String label, boolean required) {
        return new AgentInput<>(
                name,
                label,
                new ParamSpec(ParamType.BOOL, required, null, null, null, null, null, null),
                null,
                false,
                Boolean.class::cast);
    }

    public static AgentInput<String> enumeration(
            String name, String label, boolean required, Set<String> values, String dictionary) {
        return new AgentInput<>(
                name,
                label,
                new ParamSpec(ParamType.ENUM, required, null, null, null, values, null, null),
                dictionary,
                false,
                String.class::cast);
    }

    /** A bounded, distinct multi-value field; the business service must validate each referenced resource. */
    public static AgentInput<List<String>> stringList(
            String name, String label, boolean required, int minItems, int maxItems, int maxItemLength) {
        return new AgentInput<>(
                name, label, ParamSpec.stringList(required, minItems, maxItems, maxItemLength), null, false, value -> {
                    if (!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String)))
                        throw new IllegalArgumentException("Expected string list");
                    return list.stream().map(String.class::cast).toList();
                });
    }

    public static AgentInput<LocalDateTime> dateTime(String name, String label, boolean required) {
        return new AgentInput<>(
                name,
                label,
                new ParamSpec(ParamType.DATETIME, required, null, null, null, null, null, null),
                null,
                false,
                value -> LocalDateTime.parse(String.class.cast(value)));
    }

    public String name() {
        return name;
    }

    ParamSpec spec() {
        return spec;
    }

    InputFormSpec.Field field() {
        return field;
    }

    T decode(Object value) {
        return value == null ? null : decoder.apply(value);
    }
}
