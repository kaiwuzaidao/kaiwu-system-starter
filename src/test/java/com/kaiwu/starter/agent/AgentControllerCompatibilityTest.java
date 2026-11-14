package com.kaiwu.starter.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kaiwu.starter.AgentReadRuntime;
import com.kaiwu.starter.AgentWriteRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Existing consumers keep constructors, auto-configuration descriptors and request mappings. */
@SuppressWarnings("deprecation")
class AgentControllerCompatibilityTest {
    @Test
    void keepsPublishedFactoryMethodReturnTypes() throws Exception {
        assertThat(AgentExecutionAutoConfiguration.class
                        .getMethod("kaiwuAgentCapabilityController", AgentReadRuntime.class)
                        .getReturnType())
                .isEqualTo(AgentCapabilityController.class);
        assertThat(AgentWriteAutoConfiguration.class
                        .getMethod("kaiwuAgentWriteCapabilityController", AgentWriteRuntime.class)
                        .getReturnType())
                .isEqualTo(AgentWriteCapabilityController.class);
    }

    @Test
    void inheritedMappingsRemainProtectedForExistingJavaConsumers() throws Exception {
        var read = MockMvcBuilders.standaloneSetup(new AgentCapabilityController(mock(AgentReadRuntime.class)))
                .build();
        read.perform(post("/api/internal/agent/capabilities/crm.record.list/invoke")
                        .header("Authorization", "untrusted"))
                .andExpect(status().isUnauthorized());
        var write = MockMvcBuilders.standaloneSetup(new AgentWriteCapabilityController(mock(AgentWriteRuntime.class)))
                .build();
        write.perform(post("/api/internal/agent/capabilities/crm.record.save/execute")
                        .header("Authorization", "untrusted"))
                .andExpect(status().isUnauthorized());
    }
}
