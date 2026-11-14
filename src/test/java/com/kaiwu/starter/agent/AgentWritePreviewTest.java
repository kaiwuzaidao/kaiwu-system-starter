package com.kaiwu.starter.agent;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentWritePreviewTest {
    @Test
    void acceptsOnlyBoundedPlainTextWithoutCredentialFields() {
        assertThat(new AgentWritePreview(Map.of("beforeName", "旧名称", "afterName", "新名称"), 1, "{\"revision\":2}"))
                .isNotNull();
        assertThat(new AgentWritePreview(Map.of("afterName", "新名称"), Map.of("afterName", "修改后的名称"), 1, "{}")
                        .displayLabels())
                .containsEntry("afterName", "修改后的名称");
        assertThatThrownBy(() -> new AgentWritePreview(Map.of("afterName", "新名称"), Map.of("other", "其他"), 1, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentWritePreview(
                        Map.of("afterName", "新名称"), Map.of("afterName", "https://unsafe.example"), 1, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        for (String value :
                new String[] {"<script>alert(1)</script>", "https://other.test/path", "javascript:alert(1)"})
            assertThatThrownBy(() -> new AgentWritePreview(Map.of("afterName", value), 1, "{}"))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentWritePreview(Map.of("token", "hidden"), 1, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentWritePreview(Map.of("afterName", "ok"), 1, "{\"apiToken\":\"hidden\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
