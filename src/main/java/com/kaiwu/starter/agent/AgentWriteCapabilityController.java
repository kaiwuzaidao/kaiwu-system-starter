package com.kaiwu.starter.agent;

import com.kaiwu.starter.AgentWriteRuntime;

/** @deprecated Use the classified controller package; retained for existing Java consumers. */
@Deprecated(since = "0.1.1", forRemoval = false)
public final class AgentWriteCapabilityController
        extends com.kaiwu.starter.agent.controller.AgentWriteCapabilityController {
    public AgentWriteCapabilityController(AgentWriteRuntime runtime) {
        super(runtime);
    }
}
