package com.kaiwu.starter.agent;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AgentFormProtocolTest {
    private final tools.jackson.databind.json.JsonMapper json =
            tools.jackson.databind.json.JsonMapper.builder().build();

    @Test
    void bothRuntimesShareOrderedReviewedFormDigest() throws Exception {
        try (var input = getClass().getResourceAsStream("/agent-registration-form-v2.json")) {
            byte[] raw = input.readAllBytes();
            var manifest = json.readValue(raw, com.kaiwu.starter.agent.protocol.CapabilityManifest.class);
            assertThat(AgentExecutionCodec.manifestHash(manifest))
                    .isEqualTo("82ee8c855bbc2e0ad664b148153fd23f898ca388211fe47e61fe1f44b446b996");
            assertThat(manifest.inputForm().fields())
                    .extracting(field -> field.name())
                    .containsExactly("keyword", "enabled", "pageSize");
        }
    }

    @Test
    void enumDictionaryAndDateTimeHaveMatchingReviewedDigest() throws Exception {
        try (var input = getClass().getResourceAsStream("/agent-registration-form-controls.json")) {
            var manifest =
                    json.readValue(input.readAllBytes(), com.kaiwu.starter.agent.protocol.CapabilityManifest.class);
            assertThat(AgentExecutionCodec.manifestHash(manifest))
                    .isEqualTo("7c682fb051c96c1abce04681ce24d92ec75d2c805cef31cea27e80bd1fe4d90e");
        }
    }

    @Test
    void unsupportedMissingDuplicateAndMismatchedFieldsAreRejected() throws Exception {
        String base;
        try (var input = getClass().getResourceAsStream("/agent-registration-form-v2.json")) {
            base = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String bad : new String[] {
            base.replace("\"name\": \"keyword\"", "\"name\": \"unknown\""),
            base.replace("\"name\": \"enabled\"", "\"name\": \"keyword\""),
            base.replace("\"multiline\": false", "\"multiline\": true"),
            base.replace("\"type\": \"BOOL\"", "\"type\": \"OBJECT\"")
        }) {
            byte[] raw = bad.getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> json.readValue(raw, com.kaiwu.starter.agent.protocol.CapabilityManifest.class))
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
