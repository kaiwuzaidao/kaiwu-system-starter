package com.kaiwu.starter.todo.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaiwu.starter.client.ServiceAddressResolver;
import com.kaiwu.starter.todo.client.ManagedTodoResult.Disposition;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 本机真实 HTTP 验证出站路径、凭据隔离、响应分类与禁止跨主机重定向。 */
class ManagedTodoClientTest {
    /** 固定测试操作保留微秒时间和大整数用户 ID，不使用生产凭据。 */
    private static ManagedTodoOperation operation() {
        return new ManagedTodoOperation(
                "sample",
                "wf:1/task#2",
                "9223372036854775807",
                3,
                "OPEN",
                false,
                Instant.parse("2026-11-01T00:00:00.123456Z"),
                "审批",
                null,
                null,
                null);
    }

    /** 固定无敏感内容的 System 格式回执。 */
    private static String receipt(String disposition, long sequence, String state, boolean terminal) {
        return "{\"code\":0,\"data\":{\"disposition\":\"" + disposition + "\",\"storedSequence\":" + sequence
                + ",\"storedState\":\"" + state + "\",\"storedTerminal\":" + terminal
                + ",\"payloadDigest\":\"" + "a".repeat(64) + "\"}}";
    }

    /** 成功、旧版本、临时失败、错误响应与缺配置都保持可观察，绝不自动降级。 */
    @Test
    void observesProtocolResultsAndStrictlyChecksResponses() throws Exception {
        var status = new AtomicInteger(200);
        var response = new AtomicReference<>(receipt("APPLIED", 3, "OPEN", false));
        var requestBody = new AtomicReference<String>();
        var requestUri = new AtomicReference<String>();
        var count = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/managed-todos", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-only-c5");
            assertThat(exchange.getRequestHeaders().getFirst("X-Kaiwu-Delivery-Token"))
                    .isNull();
            assertThat(exchange.getRequestHeaders().getFirst("X-Kaiwu-Context")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("X-User-Id")).isNull();
            count.incrementAndGet();
            requestUri.set(exchange.getRequestURI().toASCIIString());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String address = "http://127.0.0.1:" + server.getAddress().getPort();
            var resolutions = new AtomicInteger();
            var client = new ManagedTodoClient(
                    address,
                    "sample",
                    "test-only-c5",
                    configured -> {
                        resolutions.incrementAndGet();
                        return configured.url();
                    },
                    Duration.ofSeconds(2));
            assertThat(client.putManaged(operation()).disposition()).isEqualTo(Disposition.APPLIED);
            assertThat(requestUri.get()).isEqualTo("/api/internal/managed-todos");
            assertThat(requestBody.get()).contains("9223372036854775807", "2026-11-01T00:00:00.123456Z", "审批");
            response.set(receipt("DUPLICATE", 3, "CANCELLED", false));
            assertThat(client.putManaged(operation()).disposition()).isEqualTo(Disposition.DUPLICATE);
            response.set(receipt("SUPERSEDED", 4, "DONE", true));
            assertThat(client.putManaged(operation()).disposition()).isEqualTo(Disposition.SUPERSEDED);
            response.set(receipt("SUPERSEDED", 3, "OPEN", false));
            assertThat(client.putManaged(operation()).disposition()).isEqualTo(Disposition.RETRYABLE_FAILURE);
            response.set(receipt("APPLIED", 4, "OPEN", false));
            assertThat(client.putManaged(operation()).disposition()).isEqualTo(Disposition.RETRYABLE_FAILURE);
            response.set(receipt("APPLIED", 3, "DONE", true));
            assertThat(client.putManaged(operation()).disposition()).isEqualTo(Disposition.RETRYABLE_FAILURE);
            response.set(receipt("STORED", 4, "DONE", true));
            var stored = client.getManagedReceipt("wf:1/task#2", "9223372036854775807");
            assertThat(stored.disposition()).isEqualTo(Disposition.STORED);
            assertThat(stored.receipt().storedSequence()).isEqualTo(4);
            assertThat(requestUri.get())
                    .contains("/receipt?bizKey=wf%3A1%2Ftask%232&recipientUserId=9223372036854775807");
            for (int rejected : new int[] {400, 401, 403, 404, 409, 422, 302}) {
                status.set(rejected);
                assertThat(client.putManaged(operation()).disposition()).isEqualTo(Disposition.PERMANENT_FAILURE);
            }
            for (int retryable : new int[] {408, 425, 429, 500, 503}) {
                status.set(retryable);
                assertThat(client.putManaged(operation()).disposition()).isEqualTo(Disposition.RETRYABLE_FAILURE);
            }
            status.set(200);
            for (String malformed : new String[] {
                "{}",
                "null",
                "not-json",
                "x".repeat(16_385),
                receipt("APPLIED", 3, "OPEN", false).replace("\"code\":0", "\"code\":0,\"code\":0"),
                receipt("APPLIED", 3, "OPEN", false).replace("\"code\":0", "\"code\":4294967296"),
                receipt("APPLIED", 3, "OPEN", false) + "{}"
            }) {
                response.set(malformed);
                assertThat(client.putManaged(operation()).disposition()).isEqualTo(Disposition.RETRYABLE_FAILURE);
            }
            assertThat(resolutions.get()).isEqualTo(count.get());
            var missing = new ManagedTodoClient(
                    address, "sample", null, ServiceAddressResolver.direct(), Duration.ofSeconds(1));
            assertThat(missing.putManaged(operation()).disposition()).isEqualTo(Disposition.NOT_CONFIGURED);
            assertThat(client.getManagedReceipt("key", "9223372036854775808").disposition())
                    .isEqualTo(Disposition.PERMANENT_FAILURE);
            assertThat(new ManagedTodoClient(
                                    address + "/hidden",
                                    "sample",
                                    "test-only",
                                    ServiceAddressResolver.direct(),
                                    Duration.ofSeconds(1))
                            .putManaged(operation())
                            .disposition())
                    .isEqualTo(Disposition.PERMANENT_FAILURE);
        } finally {
            server.stop(0);
        }
    }

    /** 回执 GET 也不能把 C5 凭据带到 Location 指定的其他服务。 */
    @Test
    void refusesRedirectsForBothMethodsAndClassifiesUnavailableDiscovery() throws Exception {
        var reached = new AtomicInteger();
        var target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/capture", exchange -> {
            reached.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        var redirect = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirect.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders()
                    .set("Location", "http://127.0.0.1:" + target.getAddress().getPort() + "/capture");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        target.start();
        redirect.start();
        try {
            var client = new ManagedTodoClient(
                    "http://127.0.0.1:" + redirect.getAddress().getPort(),
                    "sample",
                    "test-only",
                    ServiceAddressResolver.direct(),
                    Duration.ofSeconds(1));
            assertThat(client.putManaged(operation()).disposition()).isEqualTo(Disposition.PERMANENT_FAILURE);
            assertThat(client.getManagedReceipt("key", "1").disposition()).isEqualTo(Disposition.PERMANENT_FAILURE);
            assertThat(reached.get()).isZero();
            var unavailable = new ManagedTodoClient(
                    "lb://kaiwu-system-service",
                    "sample",
                    "test-only",
                    configured -> {
                        throw new IllegalStateException("test-only unavailable");
                    },
                    Duration.ofSeconds(1));
            assertThat(unavailable.putManaged(operation()).disposition()).isEqualTo(Disposition.RETRYABLE_FAILURE);
            assertThat(unavailable.getManagedReceipt("key", "1").disposition())
                    .isEqualTo(Disposition.RETRYABLE_FAILURE);
        } finally {
            redirect.stop(0);
            target.stop(0);
        }
    }
    /** 单次OPEN永久失败不能禁用之后的终态关闭投递；缺配置和失败永远不是成功确认。 */
    @Test
    void terminalCloseCanFollowPermanentOpenFailure() throws Exception {
        var status = new AtomicInteger(422);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = receipt("APPLIED", 4, "DONE", true).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new ManagedTodoClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "sample",
                    "test-only",
                    ServiceAddressResolver.direct(),
                    Duration.ofSeconds(2));
            assertThat(client.putManaged(operation()).disposition()).isEqualTo(Disposition.PERMANENT_FAILURE);
            status.set(200);
            var close = new ManagedTodoOperation(
                    "sample",
                    operation().bizKey(),
                    operation().recipientUserId(),
                    4,
                    "DONE",
                    true,
                    operation().operationCreatedAt(),
                    null,
                    null,
                    null,
                    null);
            assertThat(client.putManaged(close).disposition()).isEqualTo(Disposition.APPLIED);
        } finally {
            server.stop(0);
        }
    }

    /** 错误状态及超限响应即使不结束也须及时取消，不能等待远端关闭或读超时。 */
    @Test
    void cancelsUnfinishedBodiesWithoutLosingFailureClassification() throws Exception {
        for (int status : new int[] {200, 422}) {
            var release = new java.util.concurrent.CountDownLatch(1);
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(status, 0);
                try (var body = exchange.getResponseBody()) {
                    body.write(new byte[status == 200 ? 16385 : 1]);
                    body.flush();
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.io.IOException ignored) {
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var client = new ManagedTodoClient(
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        "sample",
                        "test-only",
                        ServiceAddressResolver.direct(),
                        Duration.ofSeconds(8));
                var result =
                        pool.submit(() -> client.putManaged(operation())).get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(result.disposition())
                        .isEqualTo(status == 200 ? Disposition.RETRYABLE_FAILURE : Disposition.PERMANENT_FAILURE);
                assertThat(result.receipt()).isNull();
                assertThat(result.httpStatus()).isEqualTo(status);
            } finally {
                release.countDown();
                pool.shutdownNow();
                server.stop(0);
            }
        }
    }
}
