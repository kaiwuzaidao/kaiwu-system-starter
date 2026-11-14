package com.kaiwu.starter.members.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaiwu.starter.client.ServiceAddressResolver;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 本机 HTTP 桩用于确认失败不会被当成空候选。 */
class ProjectMemberClientTest {
    /** 合法结果读取、非 2xx 与不完整资格响应均按契约处理。 */
    @Test
    void rejectsFailuresAndIncompleteEligibility() throws Exception {
        var response =
                new AtomicReference<>("{\"code\":0,\"data\":{\"roleCode\":\"approver\",\"userIds\":[\"1\",\"2\"]}}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/projects/sample/members", exchange -> {
            if (!"Bearer test-only".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1);
            } else {
                byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try {
            var client = new ProjectMemberClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "sample",
                    "test-only",
                    ServiceAddressResolver.direct(),
                    Duration.ofSeconds(2));
            assertThat(client.membersByRole("approver")).containsExactly("1", "2");
            for (String invalid : List.of(
                    "{\"data\":{\"roleCode\":\"approver\",\"userIds\":[]}}",
                    "{\"code\":4294967296,\"data\":{\"roleCode\":\"approver\",\"userIds\":[]}}",
                    "{\"code\":0,\"code\":0,\"data\":{\"roleCode\":\"approver\",\"userIds\":[]}}",
                    "{\"code\":0,\"data\":{\"roleCode\":\"approver\",\"userIds\":[\"1\",\"1\"]}}",
                    "{\"code\":0,\"data\":{\"roleCode\":\"approver\",\"userIds\":[\"9223372036854775808\"]}}",
                    "{\"code\":0,\"data\":{\"roleCode\":\"approver\",\"userIds\":[1]}}",
                    "{\"code\":0,\"data\":{\"roleCode\":\"approver\",\"userIds\":[]}} {}",
                    " ".repeat(32769),
                    "{\"code\":0,\"data\":{\"roleCode\":\"approver\",\"userIds\":["
                            + java.util.stream.IntStream.rangeClosed(1, 201)
                                    .mapToObj(id -> "\"" + id + "\"")
                                    .collect(java.util.stream.Collectors.joining(","))
                            + "]}}")) {
                response.set(invalid);
                assertThatThrownBy(() -> client.membersByRole("approver")).isInstanceOf(MemberQueryException.class);
            }
            response.set("{\"code\":0,\"data\":{\"items\":[{\"userId\":\"1\",\"eligible\":true}]}}");
            assertThatThrownBy(() -> client.eligibility(List.of("1", "2"), null))
                    .isInstanceOf(MemberQueryException.class);
            var wrong = new ProjectMemberClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "sample",
                    "wrong",
                    ServiceAddressResolver.direct(),
                    Duration.ofSeconds(2));
            assertThatThrownBy(() -> wrong.membersByRole("approver"))
                    .isInstanceOfSatisfying(MemberQueryException.class, ex -> assertThat(ex.httpStatus())
                            .isEqualTo(401));
        } finally {
            server.stop(0);
        }
    }
    /** 服务重定向不得让 Bearer 被发送到第二个入口。 */
    @Test
    void refusesRedirectsAndUnsafeOrigins() throws Exception {
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/projects/sample/members", exchange -> {
            exchange.getResponseHeaders()
                    .set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/unexpected");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/unexpected", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            var client = new ProjectMemberClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "sample",
                    "test-only",
                    ServiceAddressResolver.direct(),
                    Duration.ofSeconds(2));
            assertThatThrownBy(() -> client.membersByRole("approver"))
                    .isInstanceOfSatisfying(MemberQueryException.class, ex -> assertThat(ex.httpStatus())
                            .isEqualTo(302));
            assertThatThrownBy(() -> client.eligibility(List.of("1"), null)).isInstanceOf(MemberQueryException.class);
            assertThat(hits.get()).isZero();
            for (String origin : List.of(
                    "http://user:pass@localhost",
                    "http://localhost/path",
                    "http://localhost?x=1",
                    "http://localhost#fragment"))
                assertThatThrownBy(() -> new ProjectMemberClient(
                                origin, "sample", "test-only", ServiceAddressResolver.direct(), Duration.ofSeconds(2)))
                        .isInstanceOf(IllegalArgumentException.class);
        } finally {
            server.stop(0);
        }
    }
    /** 精确读取200人的完整快照及逐项资格；不存在时返回空列表，失败绝不替代为零成员。 */
    @Test
    void acceptsCompleteSnapshotsAndSendsOnlyScopedBearer() throws Exception {
        var response = new AtomicReference<>("{\"code\":0,\"data\":{\"roleCode\":\"approver\",\"userIds\":["
                + java.util.stream.IntStream.rangeClosed(1, 200)
                        .mapToObj(i -> "\"" + i + "\"")
                        .collect(java.util.stream.Collectors.joining(","))
                + "]}}");
        var seen = new java.util.concurrent.atomic.AtomicInteger();
        var safe = new java.util.concurrent.atomic.AtomicBoolean(true);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.incrementAndGet();
            safe.set(safe.get()
                    && "Bearer test-only".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                    && exchange.getRequestHeaders().getFirst("X-Kaiwu-Context") == null
                    && exchange.getRequestHeaders().getFirst("X-User-Id") == null
                    && exchange.getRequestHeaders().getFirst("X-Delivery-Token") == null);
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new ProjectMemberClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "sample",
                    "test-only",
                    ServiceAddressResolver.direct(),
                    Duration.ofSeconds(2));
            assertThat(client.membersByRole("approver"))
                    .hasSize(200)
                    .startsWith("1", "2")
                    .endsWith("200");
            response.set("{\"code\":0,\"data\":{\"roleCode\":\"approver\",\"userIds\":[]}}");
            assertThat(client.membersByRole("approver")).isEmpty();
            response.set(
                    "{\"code\":0,\"data\":{\"items\":[{\"userId\":\"1\",\"eligible\":true},{\"userId\":\"2\",\"eligible\":false}]}}");
            assertThat(client.eligibility(List.of("1", "2", "1"), null))
                    .containsExactly(
                            new ProjectMemberClient.Eligibility("1", true),
                            new ProjectMemberClient.Eligibility("2", false));
            response.set(
                    "{\"code\":0,\"data\":{\"items\":[{\"userId\":\"2\",\"eligible\":true},{\"userId\":\"1\",\"eligible\":false}]}}");
            assertThatThrownBy(() -> client.eligibility(List.of("1", "2"), null))
                    .isInstanceOf(MemberQueryException.class);
            int before = seen.get();
            assertThatThrownBy(() -> client.eligibility(
                            java.util.stream.IntStream.rangeClosed(1, 201)
                                    .mapToObj(Integer::toString)
                                    .toList(),
                            null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(seen.get()).isEqualTo(before);
            assertThat(safe.get()).isTrue();
            var resolvedWrong = new ProjectMemberClient(
                    "lb://system",
                    "sample",
                    "test-only",
                    ignored -> "http://user:secret@localhost/path",
                    Duration.ofSeconds(2));
            assertThatThrownBy(() -> resolvedWrong.membersByRole("approver"))
                    .isInstanceOfSatisfying(MemberQueryException.class, e -> assertThat(e.getMessage())
                            .doesNotContain("test-only", "secret"));
        } finally {
            server.stop(0);
        }
    }

    /** 对错误状态及超大成功正文都立即取消未结束的流，不因框架关闭而排空远端无限正文。 */
    @Test
    void cancelsUnfinishedErrorAndOversizedBodies() throws Exception {
        for (int status : List.of(200, 422)) {
            var release = new java.util.concurrent.CountDownLatch(1);
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.sendResponseHeaders(status, 0);
                try (var body = exchange.getResponseBody()) {
                    body.write(new byte[status == 200 ? 32769 : 1]);
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
                var client = new ProjectMemberClient(
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        "sample",
                        "test-only",
                        ServiceAddressResolver.direct(),
                        Duration.ofSeconds(8));
                var future = pool.submit(() -> {
                    try {
                        client.membersByRole("approver");
                        throw new AssertionError("query must fail");
                    } catch (MemberQueryException failure) {
                        return failure;
                    }
                });
                assertThat(future.get(2, java.util.concurrent.TimeUnit.SECONDS).httpStatus())
                        .isEqualTo(status == 200 ? 0 : 422);
            } finally {
                release.countDown();
                pool.shutdownNow();
                server.stop(0);
            }
        }
    }
}
