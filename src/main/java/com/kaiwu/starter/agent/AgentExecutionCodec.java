package com.kaiwu.starter.agent;

import com.kaiwu.starter.agent.protocol.CapabilityManifest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/** 与注册协议一致的清单摘要；调用参数拒绝重复键、尾随内容及复杂嵌套。 */
public final class AgentExecutionCodec {
    public static final int MAX_REQUEST_BYTES = 65_536;
    public static final int MAX_RESULT_BYTES = 262_144;
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(12)
                            .maxStringLength(32_768)
                            .maxNumberLength(20)
                            .build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    private AgentExecutionCodec() {}

    public static AgentInvocation decode(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_REQUEST_BYTES) throw invalid();
        return JSON.readValue(body, AgentInvocation.class);
    }

    public static AgentWriteInvocation decodeWrite(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_REQUEST_BYTES) throw invalid();
        return JSON.readValue(body, AgentWriteInvocation.class);
    }

    public static Map<String, Object> parameters(String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 16_384) throw invalid();
        Map<String, Object> values = JSON.readValue(raw, new TypeReference<Map<String, Object>>() {});
        if (values == null || values.size() > 32) throw invalid();
        return values;
    }

    public static byte[] encode(Object value) {
        return JSON.writeValueAsBytes(value);
    }

    public static String manifestHash(CapabilityManifest manifest) {
        return digest(JSON.writeValueAsString(sorted(JSON.valueToTree(manifest), "")));
    }

    private static Object sorted(JsonNode node, String field) {
        if (node.isObject()) {
            var result = new TreeMap<String, Object>();
            node.properties().forEach(entry -> result.put(entry.getKey(), sorted(entry.getValue(), entry.getKey())));
            return result;
        }
        if (node.isArray()) {
            var values = node.values().stream().map(value -> sorted(value, ""));
            // UI fields are a sequence: reordering requires a new reviewed digest.
            return "fields".equals(field)
                    ? values.toList()
                    : values.sorted(java.util.Comparator.comparing(JSON::writeValueAsString))
                            .toList();
        }
        return node;
    }

    public static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    public static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid Agent invocation");
    }
}
