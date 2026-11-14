package com.kaiwu.starter.agent;

import com.kaiwu.starter.AgentReadRuntime;

/** @deprecated Use the classified controller package; retained for existing Java consumers. */
@Deprecated(since = "0.1.1", forRemoval = false)
public final class AgentCapabilityController extends com.kaiwu.starter.agent.controller.AgentCapabilityController {
    public AgentCapabilityController(AgentReadRuntime runtime) {
        super(runtime);
    }
}
