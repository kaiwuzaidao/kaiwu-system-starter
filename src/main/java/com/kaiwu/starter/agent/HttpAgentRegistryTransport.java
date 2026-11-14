package com.kaiwu.starter.agent;

import com.kaiwu.starter.client.ServiceAddress;
import com.kaiwu.starter.client.ServiceAddressResolver;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** 固定路径、禁止重定向、请求/响应限流量及整次请求期限；每次经统一 resolver 寻址。 */
public final class HttpAgentRegistryTransport implements AgentRegistryTransport {
    public static final int MAX_BYTES = 65536;
    private static final ObjectMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(12)
                            .maxStringLength(8000)
                            .build())
                    .build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final String credential;
    private final String projectId;
    private final int timeoutMillis;
    private final ServiceAddress address;
    private final ServiceAddressResolver resolver;
    private final ThreadPoolExecutor executor;
    private final HttpClient client;

    public HttpAgentRegistryTransport(AgentRegistryProperties properties, ServiceAddressResolver resolver) {
        credential = properties.getCredential();
        if (credential == null || !credential.matches("kagent_[1-9][0-9]{0,18}_[A-Za-z0-9_-]{43}"))
            throw new IllegalArgumentException("独立 Agent 注册凭据格式错误");
        projectId = credential.split("_", 3)[1];
        timeoutMillis = properties.getTimeoutMillis();
        if (timeoutMillis < 100 || timeoutMillis > 10000)
            throw new IllegalArgumentException("Agent timeoutMillis 必须为 100..10000");
        try {
            requireBase(URI.create(properties.getSystemBaseUrl()));
            address = ServiceAddress.parse(properties.getSystemBaseUrl());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Agent System 地址格式错误，不能含路径、凭据、查询或片段");
        }
        this.resolver = resolver;
        // Fail fast for missing LB dependencies or invalid discovery results; no HTTP request here.
        target("register");
        executor = new ThreadPoolExecutor(
                2,
                2,
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(128),
                Thread.ofPlatform().daemon(true).name("kaiwu-agent-http-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMillis))
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(executor)
                .build();
    }

    private static void requireBase(URI uri) {
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("Invalid Agent base address");
    }

    private URI target(String operation) {
        URI resolved = URI.create(resolver.resolve(address));
        requireBase(resolved);
        if (!List.of("http", "https").contains(resolved.getScheme()))
            throw new IllegalArgumentException("Agent resolver must return HTTP(S)");
        return resolved.resolve("/api/internal/agent/capabilities/" + operation);
    }

    @Override
    public Revision register(String code, String version, byte[] manifest) {
        return exchange("register", manifest, code, version, null);
    }

    @Override
    public void heartbeat(Revision revision) {
        exchange(
                "heartbeat",
                JSON.writeValueAsBytes(revision),
                revision.capabilityCode(),
                revision.version(),
                revision.manifestHash());
    }

    private Revision exchange(String operation, byte[] body, String code, String version, String expectedHash) {
        if (body.length > MAX_BYTES) throw new AgentRegistryException(413);
        CompletableFuture<HttpResponse<byte[]>> future = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(target(operation))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("Authorization", "Bearer " + credential)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            future = client.sendAsync(request, info -> new BoundedBody());
            HttpResponse<byte[]> response = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) throw new AgentRegistryException(response.statusCode());
            JsonNode envelope = JSON.readTree(response.body());
            JsonNode data = envelope.path("data");
            if (!envelope.path("code").isIntegralNumber()
                    || !envelope.path("code").toString().equals("0")
                    || !projectId.equals(data.path("projectId").asString())
                    || !code.equals(data.path("capabilityCode").asString())
                    || !version.equals(data.path("version").asString())
                    || !data.path("manifestHash").isString()
                    || !data.path("manifestHash").asString().matches("[0-9a-f]{64}")
                    || !List.of("PENDING", "APPROVED")
                            .contains(data.path("status").asString())
                    || !data.path("projectId").isString()
                    || !data.path("version").isString()) throw new AgentRegistryException(502);
            String hash = data.path("manifestHash").asString();
            if (expectedHash != null && !expectedHash.equals(hash)) throw new AgentRegistryException(502);
            return new Revision(code, version, hash);
        } catch (AgentRegistryException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AgentRegistryException(0);
        } catch (Exception failure) {
            throw new AgentRegistryException(0);
        } finally {
            if (future != null && !future.isDone()) future.cancel(true);
        }
    }

    @Override
    public void close() {
        client.shutdownNow();
        executor.shutdownNow();
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new AgentRegistryException(502));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(new AgentRegistryException(0));
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
