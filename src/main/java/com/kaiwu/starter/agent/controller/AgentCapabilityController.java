package com.kaiwu.starter.agent.controller;

import com.kaiwu.starter.AgentReadRuntime;
import com.kaiwu.starter.ContextVerificationException;
import com.kaiwu.starter.StarterAuthInterceptor;
import com.kaiwu.starter.agent.AgentExecutionCodec;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 唯一接收 Agent Context 的固定端点；不进入外部 Gateway Route。 */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "kaiwu.starter.agent.execution",
        name = "enabled",
        havingValue = "true")
public class AgentCapabilityController {
    private final AgentReadRuntime runtime;

    public AgentCapabilityController(AgentReadRuntime runtime) {
        this.runtime = runtime;
    }

    @PostMapping(value = "/api/internal/agent/capabilities/{code}/invoke", produces = "application/json")
    public ResponseEntity<byte[]> invoke(@PathVariable("code") String code, HttpServletRequest request) {
        try {
            if (request.getHeader("Authorization") != null) return rejected(401);
            byte[] body = request.getInputStream().readNBytes(AgentExecutionCodec.MAX_REQUEST_BYTES + 1);
            var input = AgentExecutionCodec.decode(body);
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .body(runtime.invoke(code, input, request.getHeader(StarterAuthInterceptor.CONTEXT_HEADER)));
        } catch (ContextVerificationException failure) {
            return rejected(401);
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException failure) {
            return rejected(400);
        } catch (IOException | RuntimeException failure) {
            return rejected(503);
        }
    }

    private static ResponseEntity<byte[]> rejected(int status) {
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(AgentExecutionCodec.encode(
                        java.util.Map.of("code", status, "message", "Agent invocation unavailable")));
    }
}
