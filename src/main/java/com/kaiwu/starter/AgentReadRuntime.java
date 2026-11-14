package com.kaiwu.starter;

import com.kaiwu.starter.agent.*;
import com.kaiwu.starter.agent.protocol.CapabilityManifest;
import com.kaiwu.starter.agent.protocol.ConfirmationMode;
import com.kaiwu.starter.agent.protocol.ParamSpec;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 固定只读 Handler 分发；有界线程池、单次 Context、严格参数与 finally 清理。 */
public final class AgentReadRuntime implements AutoCloseable {
    private record Entry(AgentReadCapabilityHandler handler, CapabilityManifest manifest, String hash) {}

    private final Map<String, Entry> entries;
    private final GatewayContextVerifier verifier;
    private final AgentReplayStore replay;
    private final PermissionResolver permissions;
    private final String projectId;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16),
            Thread.ofPlatform().daemon().name("kaiwu-agent-read-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    public AgentReadRuntime(
            List<AgentReadCapabilityHandler> handlers,
            String projectCode,
            String projectId,
            GatewayContextVerifier verifier,
            AgentReplayStore replay,
            PermissionResolver permissions) {
        if (projectId == null
                || !projectId.matches("[1-9][0-9]{0,19}")
                || projectCode == null
                || projectCode.isBlank()
                || "system".equals(projectCode)) throw new IllegalArgumentException("Invalid Agent invocation");
        this.projectId = projectId;
        this.verifier = Objects.requireNonNull(verifier);
        this.replay = Objects.requireNonNull(replay);
        this.permissions = Objects.requireNonNull(permissions);
        Map<String, Entry> registry = new LinkedHashMap<>();
        for (var handler : handlers) {
            var m = Objects.requireNonNull(handler.manifest());
            if (!projectCode.equals(m.projectCode())
                    || !m.code().startsWith(projectCode + ".")
                    || !m.code().matches("[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)+")
                    || m.version() == null
                    || m.version().isBlank()
                    || m.permission() == null
                    || m.permission().isBlank()
                    || m.dataScope() == null
                    || !Boolean.TRUE.equals(m.readOnly())
                    || m.confirmation() != ConfirmationMode.NONE
                    || m.timeoutMs() == null
                    || m.timeoutMs() < 100
                    || m.timeoutMs() > 10_000
                    || m.outboundFields().isEmpty()
                    || m.params().size() > 32) throw new IllegalArgumentException("Invalid Agent invocation");
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
                        || spec.enumFrom() != null)
                    throw new IllegalArgumentException("Identity parameters are forbidden");
            });
            if (registry.putIfAbsent(m.code(), new Entry(handler, m, AgentExecutionCodec.manifestHash(m))) != null)
                throw new IllegalArgumentException("Duplicate Agent capability");
        }
        if (registry.isEmpty() || registry.size() > 100) throw new IllegalArgumentException("Invalid Agent invocation");
        this.entries = Map.copyOf(registry);
    }

    /** 仅返回已冻结的清单，避免注册与执行在不同时间读取可变 Handler 声明。 */
    public List<CapabilityManifest> manifests() {
        return entries.values().stream().map(Entry::manifest).toList();
    }

    public byte[] invoke(String pathCode, AgentInvocation request, String token) {
        Entry entry = entries.get(pathCode);
        if (entry == null
                || request == null
                || !pathCode.equals(request.code())
                || !entry.manifest().version().equals(request.version())
                || !entry.hash().equals(request.manifestHash())
                || request.correlationId() == null
                || !request.correlationId().matches("[A-Za-z0-9-]{1,64}"))
            throw new ContextVerificationException("Agent capability unavailable");
        Map<String, Object> parameters =
                validate(entry.manifest(), AgentExecutionCodec.parameters(request.paramsJson()));
        var actor = verifier.verifyAgent(
                token,
                pathCode,
                request.version(),
                entry.hash(),
                AgentExecutionCodec.digest(request.paramsJson()),
                request.correlationId());
        if (!projectId.equals(actor.projectId())
                || !permissions.hasPermission(actor, entry.manifest().permission()))
            throw new ContextVerificationException("Agent capability unavailable");
        // 保留到验证器时钟容差之外，不能在 Handler 失败后释放 jti。
        if (!replay.claim(actor.tokenId(), actor.expiresAt().plusSeconds(60)))
            throw new ContextVerificationException("Agent Context already consumed");
        Future<byte[]> task = executor.submit(() -> {
            try {
                if (!actor.expiresAt().isAfter(Instant.now()))
                    throw new ContextVerificationException("Agent Context expired");
                StarterContext.set(actor);
                org.slf4j.MDC.put("traceId", request.correlationId());
                org.slf4j.MDC.put("userId", actor.userId());
                org.slf4j.MDC.put("projectId", actor.projectId());
                var result = entry.handler().query(parameters, actor);
                if (result == null || result.size() > 100)
                    throw new IllegalArgumentException("Invalid Agent invocation");
                var projected = new ArrayList<Map<String, Object>>();
                for (var row : result) {
                    if (row == null) throw new IllegalArgumentException("Invalid Agent invocation");
                    var output = new LinkedHashMap<String, Object>();
                    for (String field : entry.manifest().outboundFields()) {
                        Object value = row.get(field);
                        if (value != null
                                && !(value instanceof String || value instanceof Number || value instanceof Boolean))
                            throw new IllegalArgumentException("Invalid Agent invocation");
                        if (value instanceof String text && text.length() > 8000)
                            throw new IllegalArgumentException("Invalid Agent invocation");
                        if (row.containsKey(field)) output.put(field, value);
                    }
                    projected.add(output);
                }
                byte[] encoded = AgentExecutionCodec.encode(Map.of("rows", projected));
                if (encoded.length > AgentExecutionCodec.MAX_RESULT_BYTES)
                    throw new IllegalArgumentException("Invalid Agent invocation");
                return encoded;
            } finally {
                StarterContext.clear();
                org.slf4j.MDC.remove("traceId");
                org.slf4j.MDC.remove("userId");
                org.slf4j.MDC.remove("projectId");
            }
        });
        try {
            return task.get(entry.manifest().timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            throw new IllegalStateException("Agent invocation interrupted");
        } catch (ExecutionException | TimeoutException failure) {
            task.cancel(true);
            throw new IllegalStateException("Agent invocation unavailable");
        }
    }

    static Map<String, Object> validate(CapabilityManifest manifest, Map<String, Object> params) {
        if (!manifest.params().keySet().containsAll(params.keySet()))
            throw new IllegalArgumentException("Invalid Agent invocation");
        Map<String, Object> result = new LinkedHashMap<>();
        manifest.params().forEach((name, spec) -> {
            Object value = params.containsKey(name) ? params.get(name) : spec.defaultValue();
            if (value == null) {
                if (spec.required()) throw new IllegalArgumentException("Invalid Agent invocation");
                return;
            }
            validateValue(spec, value);
            result.put(name, value);
        });
        return Collections.unmodifiableMap(result);
    }

    private static void validateValue(ParamSpec spec, Object value) {
        switch (spec.type()) {
            case STRING -> {
                if (!(value instanceof String text)
                        || (spec.required() && text.isBlank())
                        || (spec.maxLength() != null && text.length() > spec.maxLength()))
                    throw new IllegalArgumentException("Invalid Agent invocation");
            }
            case STRING_LIST -> {
                if (!(value instanceof java.util.List<?> list)
                        || spec.min() == null
                        || spec.max() == null
                        || spec.maxLength() == null
                        || list.size() < spec.min()
                        || list.size() > spec.max()
                        || list.stream()
                                .anyMatch(item -> !(item instanceof String text)
                                        || text.isBlank()
                                        || !text.equals(text.trim())
                                        || text.length() > spec.maxLength())
                        || new java.util.HashSet<>(list).size() != list.size())
                    throw new IllegalArgumentException("Invalid Agent invocation");
            }
            case ENUM -> {
                if (!(value instanceof String)
                        || spec.values() == null
                        || !spec.values().contains(value))
                    throw new IllegalArgumentException("Invalid Agent invocation");
            }
            case BOOL -> {
                if (!(value instanceof Boolean)) throw new IllegalArgumentException("Invalid Agent invocation");
            }
            case DATETIME -> {
                if (!(value instanceof String text)) throw new IllegalArgumentException("Invalid Agent invocation");
                java.time.LocalDateTime.parse(text);
            }
            case INT -> {
                if (!(value instanceof Number)) throw new IllegalArgumentException("Invalid Agent invocation");
                int integer;
                try {
                    integer = new BigDecimal(value.toString()).intValueExact();
                } catch (ArithmeticException | NumberFormatException failure) {
                    throw new IllegalArgumentException("Invalid Agent invocation");
                }
                if ((spec.min() != null && integer < spec.min()) || (spec.max() != null && integer > spec.max()))
                    throw new IllegalArgumentException("Invalid Agent invocation");
            }
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
