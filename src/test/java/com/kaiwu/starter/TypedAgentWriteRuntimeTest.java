package com.kaiwu.starter;

import com.kaiwu.starter.agent.*;
import com.kaiwu.starter.agent.typed.*;
import java.util.*;

/** Runs the complete signed-context/write-ledger contract against the typed adapter as well. */
class TypedAgentWriteRuntimeTest extends AgentWriteRuntimeTest {
    record Result(String recordId, String name, String secret) {}

    @Override
    AgentWriteCapabilityHandler adapt(AgentWriteCapabilityHandler original) {
        var m = original.manifest();
        var name = AgentInput.text("name", "名称", true, 30, false);
        return new TypedAgentWrite<String, Result>(
                new TypedAgentWrite.Identity(
                        m.projectCode(), m.code(), m.version(), m.description(), m.permission(), m.dataScope()),
                new AgentInputSchema<>("重命名记录", List.of(name), args -> args.get(name)),
                List.of(
                        AgentOutput.text("recordId", "记录编号", Result::recordId),
                        AgentOutput.text("name", "名称", Result::name)),
                m.confirmation(),
                m.timeoutMs(),
                (input, actor) -> original.preview(Map.of("name", input), actor),
                (input, preconditions, actor) -> {
                    var result = AgentExecutionCodec.parameters(
                            original.execute(Map.of("name", input), preconditions, actor));
                    return new Result((String) result.get("recordId"), (String) result.get("name"), (String)
                            result.get("secret"));
                });
    }
}
