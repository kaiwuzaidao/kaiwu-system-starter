package com.kaiwu.starter.agent;

import static org.assertj.core.api.Assertions.*;

import com.kaiwu.starter.agent.protocol.CapabilityManifest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AgentRegistryProtocolTest {
    @Test
    void independentDtoMatchesSystemContractFixture() throws Exception {
        var json = JsonMapper.builder().build();
        try (var input = getClass().getResourceAsStream("/agent-registration-v1.json")) {
            assertThat(input).isNotNull();
            var expected = json.readTree(input.readAllBytes());
            var manifest = json.treeToValue(expected, CapabilityManifest.class);
            assertThat(manifest).isEqualTo(AgentRegistryLifecycleTest.manifest("crm.record.list"));
            // Collection order is immaterial to the wire protocol; compare decoded trees by field.
            var serialized = json.valueToTree(manifest);
            assertThat(serialized.properties().stream()
                            .map(java.util.Map.Entry::getKey)
                            .toList())
                    .containsExactlyInAnyOrderElementsOf(expected.properties().stream()
                            .map(java.util.Map.Entry::getKey)
                            .toList());
            assertThat(json.treeToValue(serialized, CapabilityManifest.class)).isEqualTo(manifest);
        }
    }
}
