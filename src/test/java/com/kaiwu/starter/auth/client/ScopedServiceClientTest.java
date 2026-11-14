package com.kaiwu.starter.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaiwu.starter.client.ServiceAddressResolver;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 验证C5公共Bearer传输，不提前引入C2/C3/C4端点客户端。 */
class ScopedServiceClientTest {
    /** 每个实例显式绑定凭据及声明scope，诊断对象不得打印令牌。 */
    private ScopedServiceClient client(String address) {
        return new ScopedServiceClient(
                address,
                new ScopedServiceClient.Credential("workflow", Set.of("member:read"), "synthetic-c5-token"),
                ServiceAddressResolver.direct(),
                Duration.ofSeconds(3));
    }

    /** 真实HTTP只发送Authorization Bearer，不伪造用户Context或回退旧delivery-token。 */
    @Test
    void sendsOnlyExplicitBearerAndBoundsTheResponse() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/scoped-probe", exchange -> {
            calls.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-c5-token");
            assertThat(exchange.getRequestHeaders().getFirst("X-Kaiwu-Context")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("X-Kaiwu-Delivery-Token"))
                    .isNull();
            byte[] body = "{\"code\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var transport = client("http://127.0.0.1:" + server.getAddress().getPort());
            var result = transport.exchange(
                    new ScopedServiceClient.Endpoint("/api/internal/scoped-probe", "member:read"), "POST", new byte[0]);
            assertThat(result.outcome()).isEqualTo(ScopedServiceClient.Outcome.SUCCESS);
            assertThat(result.status()).isEqualTo(200);
            assertThat(calls).hasValue(1);
            assertThat(new String(result.body(), StandardCharsets.UTF_8)).isEqualTo("{\"code\":0}");
            assertThat(result.toString()).doesNotContain("code", "synthetic-c5-token");
            var denied = transport.exchange(
                    new ScopedServiceClient.Endpoint("/api/internal/scoped-probe", "todo:managed"),
                    "POST",
                    new byte[0]);
            assertThat(denied.outcome()).isEqualTo(ScopedServiceClient.Outcome.SCOPE_DENIED);
            assertThat(calls).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    /** 重定向不能把凭据转发到其他原点，403与503均保持可观察的失败状态且不返回正文。 */
    @Test
    void neverFollowsRedirectsOrReturnsErrorBodies() throws Exception {
        var status = new AtomicInteger(302);
        var leaked = new AtomicInteger();
        var target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> {
            leaked.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        target.start();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders()
                    .add("Location", "http://127.0.0.1:" + target.getAddress().getPort() + "/");
            byte[] body = "sensitive error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var transport = client("http://127.0.0.1:" + server.getAddress().getPort());
            for (int code : new int[] {302, 403, 503}) {
                status.set(code);
                var result = transport.exchange(
                        new ScopedServiceClient.Endpoint("/api/internal/scoped-probe", "member:read"),
                        "GET",
                        new byte[0]);
                assertThat(result.outcome()).isEqualTo(ScopedServiceClient.Outcome.REJECTED);
                assertThat(result.status()).isEqualTo(code);
                assertThat(result.body()).isEmpty();
            }
            assertThat(leaked).hasValue(0);
        } finally {
            server.stop(0);
            target.stop(0);
        }
    }

    /** 超过原feature的32KiB响应界限时取消正文，不能把截断数据交给调用方当成功。 */
    @Test
    void cancelsOversizedResponses() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write(new byte[32769]);
                body.flush();
            } catch (java.io.IOException ignored) {
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var result = client("http://127.0.0.1:" + server.getAddress().getPort())
                    .exchange(
                            new ScopedServiceClient.Endpoint("/api/internal/scoped-probe", "member:read"),
                            "GET",
                            new byte[0]);
            assertThat(result.outcome()).isEqualTo(ScopedServiceClient.Outcome.RESPONSE_LIMIT);
            assertThat(result.body()).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    /** 已超限但尚未结束的chunked正文必须主动取消，不能等远端关闭或超时后才返回。 */
    @Test
    void doesNotDrainAnUnfinishedOversizedBody() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write(new byte[32769]);
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
            var future = pool.submit(
                    () -> client("http://127.0.0.1:" + server.getAddress().getPort())
                            .exchange(
                                    new ScopedServiceClient.Endpoint("/api/internal/scoped-probe", "member:read"),
                                    "GET",
                                    new byte[0]));
            assertThat(future.get(2, java.util.concurrent.TimeUnit.SECONDS).outcome())
                    .isEqualTo(ScopedServiceClient.Outcome.RESPONSE_LIMIT);
        } finally {
            release.countDown();
            pool.shutdownNow();
            server.stop(0);
        }
    }

    /** 配置与寻址中的userinfo、路径、query及默认服务不能形成凭据外带通道。 */
    @Test
    void rejectsUnsafeConfigurationAndSanitizesResolverFailure() {
        for (String address : new String[] {
            "http://user:secret@localhost", "http://localhost/path", "http://localhost/?token=x", "file:///secret"
        }) assertThatThrownBy(() -> client(address)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScopedServiceClient.Credential("default", Set.of("member:read"), "t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScopedServiceClient.Endpoint("/api/internal/../admin", "member:read"))
                .isInstanceOf(IllegalArgumentException.class);
        var credential = new ScopedServiceClient.Credential("workflow", Set.of("member:read"), "synthetic-c5-token");
        assertThat(credential.toString()).doesNotContain("synthetic-c5-token");
        var transport = new ScopedServiceClient(
                "lb://system", credential, ignored -> "http://malicious/path", Duration.ofSeconds(1));
        var result = transport.exchange(
                new ScopedServiceClient.Endpoint("/api/internal/scoped-probe", "member:read"), "GET", new byte[0]);
        assertThat(result.outcome()).isEqualTo(ScopedServiceClient.Outcome.UNAVAILABLE);
        assertThat(result.body()).isEmpty();
    }
}
