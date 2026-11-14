package com.kaiwu.starter.agent;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/** 业务方提供的只读预览；展示字段需已脱敏，前置条件交由平台冻结并绑定摘要。 */
public record AgentWritePreview(
        Map<String, String> displayFields,
        Map<String, String> displayLabels,
        int affectedCount,
        String preconditionsJson) {
    public AgentWritePreview(Map<String, String> displayFields, int affectedCount, String preconditionsJson) {
        this(displayFields, Map.of(), affectedCount, preconditionsJson);
    }

    public AgentWritePreview {
        if (displayFields == null
                || displayFields.size() > 32
                || affectedCount < 0
                || affectedCount > 100
                || preconditionsJson == null
                || preconditionsJson.getBytes(StandardCharsets.UTF_8).length > 16_384)
            throw new IllegalArgumentException("Invalid Agent write preview");
        if (displayLabels == null || displayLabels.size() > displayFields.size())
            throw new IllegalArgumentException("Invalid Agent write preview");
        Map<String, String> safeFields = Map.copyOf(displayFields);
        Map<String, String> safeLabels = Map.copyOf(displayLabels);
        displayFields = safeFields;
        displayLabels = safeLabels;
        if (AgentExecutionCodec.encode(displayFields).length > 32_768)
            throw new IllegalArgumentException("Invalid Agent write preview");
        safeFields.forEach((name, value) -> {
            if (name == null
                    || !name.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")
                    || forbiddenName(name)
                    || value == null
                    || value.length() > 8000
                    || unsafeText(value)) throw new IllegalArgumentException("Invalid Agent write preview");
        });
        safeLabels.forEach((name, label) -> {
            if (!safeFields.containsKey(name)
                    || label == null
                    || label.isBlank()
                    || label.length() > 120
                    || unsafeText(label)) throw new IllegalArgumentException("Invalid Agent write preview");
        });
        AgentExecutionCodec.parameters(preconditionsJson).forEach((name, value) -> {
            if (forbiddenName(name)
                    || (value != null
                            && !(value instanceof String || value instanceof Number || value instanceof Boolean))
                    || (value instanceof String text && text.length() > 8000))
                throw new IllegalArgumentException("Invalid Agent write preview");
        });
    }

    private static boolean forbiddenName(String name) {
        String normalized = name.replace("_", "").toLowerCase(Locale.ROOT);
        return normalized.contains("url")
                || normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("credential")
                || normalized.contains("privatekey")
                || normalized.contains("authorization")
                || normalized.contains("context");
    }

    private static boolean unsafeText(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return value.indexOf('<') >= 0
                || value.indexOf('>') >= 0
                || lower.contains("http://")
                || lower.contains("https://")
                || lower.contains("javascript:")
                || lower.contains("data:")
                || value.chars().anyMatch(c -> c < 0x20 && c != '\n' && c != '\t');
    }

    @Override
    public String toString() {
        return "AgentWritePreview[redacted]";
    }
}
