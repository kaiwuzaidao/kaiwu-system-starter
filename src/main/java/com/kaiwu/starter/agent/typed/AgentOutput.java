package com.kaiwu.starter.agent.typed;

import com.kaiwu.starter.agent.protocol.OutputViewSpec;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Function;

/** Explicit safe projection: unlisted entity properties are never inspected or serialized. */
public final class AgentOutput<R> {
    private final OutputViewSpec.Field field;
    private final Function<R, ?> getter;

    private AgentOutput(
            String name, String label, OutputViewSpec.Format format, String dictionary, Function<R, ?> getter) {
        this.field = new OutputViewSpec.Field(name, label, format, dictionary);
        this.getter = Objects.requireNonNull(getter);
    }

    public static <R> AgentOutput<R> text(String name, String label, Function<R, String> getter) {
        return new AgentOutput<>(name, label, OutputViewSpec.Format.TEXT, null, getter);
    }

    public static <R> AgentOutput<R> number(String name, String label, Function<R, ? extends Number> getter) {
        return new AgentOutput<>(name, label, OutputViewSpec.Format.TEXT, null, getter);
    }

    public static <R> AgentOutput<R> bool(String name, String label, Function<R, Boolean> getter) {
        return new AgentOutput<>(name, label, OutputViewSpec.Format.BOOLEAN_ENABLED, null, getter);
    }

    /** Retains existing integer boolean wire values while using the managed boolean presentation. */
    public static <R> AgentOutput<R> enabled(String name, String label, Function<R, Integer> getter) {
        return new AgentOutput<>(name, label, OutputViewSpec.Format.BOOLEAN_ENABLED, null, getter);
    }

    public static <R> AgentOutput<R> dictionary(
            String name, String label, String dictionary, Function<R, String> getter) {
        return new AgentOutput<>(name, label, OutputViewSpec.Format.DICTIONARY, dictionary, getter);
    }

    public static <R> AgentOutput<R> dateTime(String name, String label, Function<R, LocalDateTime> getter) {
        Objects.requireNonNull(getter);
        return new AgentOutput<>(name, label, OutputViewSpec.Format.DATETIME, null, row -> {
            LocalDateTime value = getter.apply(row);
            return value == null ? null : value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        });
    }

    OutputViewSpec.Field field() {
        return field;
    }

    Object value(R row) {
        return getter.apply(row);
    }
}
