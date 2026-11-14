package com.kaiwu.starter;

import com.kaiwu.starter.agent.*;
import com.kaiwu.starter.agent.protocol.CapabilityManifest;
import com.kaiwu.starter.agent.protocol.ConfirmationMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.MDC;

/** 固定写 Handler 分发；三种用途各自验证，执行结果在业务事务账本中持久化。 */
public final class AgentWriteRuntime implements AutoCloseable {
    private record Entry(AgentWriteCapabilityHandler handler, CapabilityManifest manifest, String hash) {}

    private final Map<String, Entry> entries;
    private final String projectCode;
    private final String projectId;
    private final GatewayContextVerifier verifier;
    private final AgentReplayStore replay;
    private final AgentWriteLedger ledger;
    private final PermissionResolver permissions;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16),
            Thread.ofPlatform().daemon().name("kaiwu-agent-write-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    public AgentWriteRuntime(
            List<AgentWriteCapabilityHandler> handlers,
            String projectCode,
            String projectId,
            GatewayContextVerifier verifier,
            AgentReplayStore replay,
            AgentWriteLedger ledger,
            PermissionResolver permissions) {
        if (projectId == null
                || !projectId.matches("[1-9][0-9]{0,19}")
                || projectCode == null
                || projectCode.isBlank()
                || "system".equals(projectCode)) throw AgentExecutionCodec.invalid();
        this.projectCode = projectCode;
        this.projectId = projectId;
        this.verifier = Objects.requireNonNull(verifier);
        this.replay = Objects.requireNonNull(replay);
        this.ledger = Objects.requireNonNull(ledger);
        this.permissions = Objects.requireNonNull(permissions);
        Map<String, Entry> registry = new LinkedHashMap<>();
        for (var handler : handlers) {
            var manifest = Objects.requireNonNull(handler.manifest());
            validateManifest(manifest, projectCode);
            if (registry.putIfAbsent(
                            manifest.code(), new Entry(handler, manifest, AgentExecutionCodec.manifestHash(manifest)))
                    != null) throw AgentExecutionCodec.invalid();
        }
        if (registry.isEmpty() || registry.size() > 100) throw AgentExecutionCodec.invalid();
        this.entries = Map.copyOf(registry);
    }

    public List<CapabilityManifest> manifests() {
        return entries.values().stream().map(Entry::manifest).toList();
    }

    public byte[] invoke(String operation, String pathCode, AgentWriteInvocation request, String token) {
        Entry entry = entries.get(pathCode);
        if (request == null
                || pathCode == null
                || !pathCode.equals(request.code())
                || !pathCode.startsWith(projectCode + ".")
                || (!"RESULT".equals(operation)
                        && (entry == null
                                || !entry.manifest().version().equals(request.version())
                                || !entry.hash().equals(request.manifestHash()))))
            throw new ContextVerificationException("Agent capability unavailable");
        // RESULT 只查已提交的业务账本。旧版本 Handler 下线后仍需按原冻结绑定核实结果。
        Map<String, Object> parameters = "RESULT".equals(operation)
                ? AgentExecutionCodec.parameters(request.paramsJson())
                : AgentReadRuntime.validate(entry.manifest(), AgentExecutionCodec.parameters(request.paramsJson()));
        String preconditionsHash =
                request.preconditionsJson() == null ? null : AgentExecutionCodec.digest(request.preconditionsJson());
        if (preconditionsHash != null) AgentExecutionCodec.parameters(request.preconditionsJson());
        AgentWriteBinding binding = new AgentWriteBinding(
                operation,
                pathCode,
                request.version(),
                request.manifestHash(),
                AgentExecutionCodec.digest(request.paramsJson()),
                preconditionsHash,
                request.previewHash(),
                request.actionId(),
                request.actionExpiresAt(),
                request.correlationId());
        var actor = verifier.verifyAgentWrite(token, binding);
        if (!projectId.equals(actor.projectId())
                || (!"RESULT".equals(operation)
                        && !permissions.hasPermission(actor, entry.manifest().permission())))
            throw new ContextVerificationException("Agent capability unavailable");
        if (!replay.claim(actor.tokenId(), actor.expiresAt().plusSeconds(60)))
            throw new ContextVerificationException("Agent Context already consumed");
        Future<byte[]> task = executor.submit(() -> {
            try {
                if (!actor.expiresAt().isAfter(Instant.now()))
                    throw new ContextVerificationException("Agent Context expired");
                StarterContext.set(actor);
                MDC.put("traceId", request.correlationId());
                MDC.put("userId", actor.userId());
                MDC.put("projectId", actor.projectId());
                return switch (operation) {
                    case "PREVIEW" -> preview(entry, parameters, actor);
                    case "EXECUTE" -> execute(entry, parameters, request, actor);
                    case "RESULT" -> result(request, actor);
                    default -> throw AgentExecutionCodec.invalid();
                };
            } finally {
                StarterContext.clear();
                MDC.remove("traceId");
                MDC.remove("userId");
                MDC.remove("projectId");
            }
        });
        try {
            return task.get("RESULT".equals(operation) ? 3000 : entry.manifest().timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            throw new IllegalStateException("Agent write interrupted");
        } catch (ExecutionException | TimeoutException failure) {
            task.cancel(true);
            throw new IllegalStateException("Agent write outcome unavailable");
        }
    }

    private static byte[] preview(Entry entry, Map<String, Object> parameters, KaiwuContext actor) {
        AgentWritePreview preview = entry.handler().preview(parameters, actor);
        if (preview == null) throw AgentExecutionCodec.invalid();
        byte[] encoded = AgentExecutionCodec.encode(preview);
        if (encoded.length > AgentExecutionCodec.MAX_RESULT_BYTES) throw AgentExecutionCodec.invalid();
        return encoded;
    }

    private byte[] execute(
            Entry entry, Map<String, Object> parameters, AgentWriteInvocation request, KaiwuContext actor) {
        AgentWriteAction action = action(request, actor);
        String response = ledger.executeOnce(
                action,
                () -> projectResult(
                        entry.manifest(), entry.handler().execute(parameters, request.preconditionsJson(), actor)));
        return AgentExecutionCodec.encode(
                Map.of("state", "SUCCEEDED", "result", AgentExecutionCodec.parameters(response)));
    }

    private byte[] result(AgentWriteInvocation request, KaiwuContext actor) {
        return ledger.result(action(request, actor))
                .map(value -> AgentExecutionCodec.encode(
                        Map.of("state", "SUCCEEDED", "result", AgentExecutionCodec.parameters(value))))
                .orElseGet(() -> AgentExecutionCodec.encode(Map.of("state", "UNKNOWN")));
    }

    private static AgentWriteAction action(AgentWriteInvocation request, KaiwuContext actor) {
        UUID actionId;
        try {
            actionId = UUID.fromString(request.actionId());
            if (!actionId.toString().equals(request.actionId())) throw AgentExecutionCodec.invalid();
        } catch (RuntimeException invalid) {
            throw AgentExecutionCodec.invalid();
        }
        return new AgentWriteAction(
                actor.projectId(),
                actionId,
                actor.userId(),
                actor.sessionId(),
                request.code(),
                request.version(),
                request.manifestHash(),
                AgentExecutionCodec.digest(request.paramsJson()),
                AgentExecutionCodec.digest(request.preconditionsJson()),
                request.previewHash(),
                request.actionExpiresAt());
    }

    private static String projectResult(CapabilityManifest manifest, String raw) {
        Map<String, Object> result = AgentExecutionCodec.parameters(raw);
        Map<String, Object> projected = new LinkedHashMap<>();
        for (String field : manifest.outboundFields()) {
            if (!result.containsKey(field)) continue;
            Object value = result.get(field);
            if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean))
                throw AgentExecutionCodec.invalid();
            if (value instanceof String text && text.length() > 8000) throw AgentExecutionCodec.invalid();
            projected.put(field, value);
        }
        byte[] bytes = AgentExecutionCodec.encode(projected);
        if (bytes.length > 16_384) throw AgentExecutionCodec.invalid();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void validateManifest(CapabilityManifest m, String projectCode) {
        if (!projectCode.equals(m.projectCode())
                || m.code() == null
                || !m.code().startsWith(projectCode + ".")
                || !m.code().matches("[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)+")
                || m.version() == null
                || m.version().isBlank()
                || m.permission() == null
                || m.permission().isBlank()
                || m.dataScope() == null
                || !Boolean.FALSE.equals(m.readOnly())
                || !Boolean.TRUE.equals(m.idempotent())
                || !(m.confirmation() == ConfirmationMode.REQUIRED || m.confirmation() == ConfirmationMode.STRONG)
                || m.timeoutMs() == null
                || m.timeoutMs() < 100
                || m.timeoutMs() > 10_000
                || m.outboundFields().isEmpty()
                || m.params().size() > 32) throw AgentExecutionCodec.invalid();
        m.params().forEach((name, spec) -> {
            String normalized = name.replace("_", "").toLowerCase(Locale.ROOT);
            if (Set.of(
                                    "userid",
                                    "owneruserid",
                                    "tenantid",
                                    "rolecode",
                                    "rolecodes",
                                    "roles",
                                    "roleid",
                                    "roleids",
                                    "operator",
                                    "actor",
                                    "projectid",
                                    "projectcode",
                                    "sessionid",
                                    "sid",
                                    "permissions",
                                    "permission",
                                    "audience",
                                    "authzversion",
                                    "correlationid",
                                    "actionid",
                                    "idempotencykey")
                            .contains(normalized)
                    || spec.enumFrom() != null) throw AgentExecutionCodec.invalid();
        });
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
