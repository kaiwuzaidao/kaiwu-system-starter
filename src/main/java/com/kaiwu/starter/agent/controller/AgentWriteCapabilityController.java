package com.kaiwu.starter.agent.controller;

import com.kaiwu.starter.AgentWriteRuntime;
import com.kaiwu.starter.ContextVerificationException;
import com.kaiwu.starter.StarterAuthInterceptor;
import com.kaiwu.starter.agent.AgentExecutionCodec;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 固定的预览、执行和结果查询入口；用途由签名 Context 而非请求字段决定。 */
@RestController
@ConditionalOnProperty(prefix = "kaiwu.starter.agent.write", name = "enabled", havingValue = "true")
public class AgentWriteCapabilityController {
    private final AgentWriteRuntime runtime;

    public AgentWriteCapabilityController(AgentWriteRuntime runtime) {
        this.runtime = runtime;
    }

    @PostMapping(value = "/api/internal/agent/capabilities/{code}/preview", produces = "application/json")
    public ResponseEntity<byte[]> preview(@PathVariable("code") String code, HttpServletRequest request) {
        return invoke("PREVIEW", code, request);
    }

    @PostMapping(value = "/api/internal/agent/capabilities/{code}/execute", produces = "application/json")
    public ResponseEntity<byte[]> execute(@PathVariable("code") String code, HttpServletRequest request) {
        return invoke("EXECUTE", code, request);
    }

    @PostMapping(value = "/api/internal/agent/capabilities/{code}/result", produces = "application/json")
    public ResponseEntity<byte[]> result(@PathVariable("code") String code, HttpServletRequest request) {
        return invoke("RESULT", code, request);
    }

    private ResponseEntity<byte[]> invoke(String operation, String code, HttpServletRequest request) {
        try {
            if (request.getHeader("Authorization") != null) return rejected(401);
            byte[] body = request.getInputStream().readNBytes(AgentExecutionCodec.MAX_REQUEST_BYTES + 1);
            var input = AgentExecutionCodec.decodeWrite(body);
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .body(runtime.invoke(
                            operation, code, input, request.getHeader(StarterAuthInterceptor.CONTEXT_HEADER)));
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
                .body(AgentExecutionCodec.encode(Map.of("code", status, "message", "Agent invocation unavailable")));
    }
}
