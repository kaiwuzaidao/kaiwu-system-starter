package com.kaiwu.datascope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaiwu.starter.client.ServiceAddressResolver;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 默认成员查询：请求形态、严格解析和失败关闭（对应 System 的 placement 接口）。 */
class SystemMemberDirectoryTest {

    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body;

    /** 启动本地桩服务，响应内容由各用例设置。 */
    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    /** 停止桩服务。 */
    @AfterEach
    void stop() {
        server.stop(0);
    }

    private SystemMemberDirectory directory() {
        return new SystemMemberDirectory(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "crm",
                "zsch_1_test-only",
                ServiceAddressResolver.direct(),
                Duration.ofSeconds(2));
    }

    /** 有效成员：固定路径、Bearer 凭据、只带用户 ID；部门按数值解析。 */
    @Test
    void parsesAnActiveMemberAndSendsOnlyTheUserId() {
        body = "{\"code\":0,\"data\":{\"userId\":\"1000\",\"active\":true,\"departmentIds\":[\"7001\",\"7002\"]}}";

        MemberDirectory.Placement placement = directory().placement("1000");

        assertThat(placement.active()).isTrue();
        assertThat(placement.departmentIds()).containsExactlyInAnyOrder(7001L, 7002L);
        assertThat(path.get()).isEqualTo("/api/internal/projects/crm/members/placement");
        assertThat(authorization.get()).isEqualTo("Bearer zsch_1_test-only");
        assertThat(requestBody.get()).isEqualTo("{\"userId\":\"1000\"}");
    }

    /** 非有效成员：active=false 且没有部门。 */
    @Test
    void parsesAnInactiveMember() {
        body = "{\"code\":0,\"data\":{\"userId\":\"1000\",\"active\":false,\"departmentIds\":[]}}";

        assertThat(directory().placement("1000").active()).isFalse();
    }

    /** 任何拒绝、故障或畸形响应都抛异常，绝不当作「不是成员」。 */
    @Test
    void everyFailureThrowsInsteadOfSayingNotAMember() {
        for (int code : new int[] {401, 403, 404, 500}) {
            status = code;
            body = "{\"code\":" + code + ",\"data\":null}";
            assertThatThrownBy(() -> directory().placement("1000"))
                    .as("HTTP " + code)
                    .isInstanceOf(RuntimeException.class);
        }
        status = 200;
        for (String bad : new String[] {
            "{\"code\":0,\"data\":{\"userId\":\"2000\",\"active\":true,\"departmentIds\":[]}}",
            "{\"code\":0,\"data\":{\"userId\":\"1000\",\"active\":\"yes\",\"departmentIds\":[]}}",
            "{\"code\":0,\"data\":{\"userId\":\"1000\",\"active\":true,\"departmentIds\":[7001]}}",
            "{\"code\":0,\"data\":{\"userId\":\"1000\",\"active\":true,\"departmentIds\":[\"7001\",\"7001\"]}}",
            "{\"code\":0,\"data\":{\"userId\":\"1000\",\"active\":false,\"departmentIds\":[\"7001\"]}}",
            "{\"code\":1,\"data\":{\"userId\":\"1000\",\"active\":true,\"departmentIds\":[]}}",
            "{\"code\":0,\"data\":null}",
            "not json",
        }) {
            body = bad;
            assertThatThrownBy(() -> directory().placement("1000")).as(bad).isInstanceOf(RuntimeException.class);
        }
        body = "{\"code\":0,\"data\":{\"userId\":\"1000\",\"active\":true,\"departmentIds\":[]},\"pad\":\""
                + "x".repeat(20_000) + "\"}";
        assertThatThrownBy(() -> directory().placement("1000")).isInstanceOf(RuntimeException.class);
    }

    /** 非法用户 ID 与非法配置在发请求前就被拒绝。 */
    @Test
    void rejectsBadInputsBeforeAnyRequest() {
        assertThatThrownBy(() -> directory().placement("abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> directory().placement("0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SystemMemberDirectory(
                        "http://127.0.0.1:1", "Bad Code", "x", ServiceAddressResolver.direct(), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(path.get()).isNull();
    }

    /** reassign 把成员查询的故障包装成数据范围违规，而不是放行或当作非成员。 */
    @Test
    void reassignTurnsDirectoryFailureIntoViolation() {
        status = 500;
        body = "{}";
        MySqlHarness.as(1, 10, java.util.Map.of());
        MySqlHarness.install(directory());
        try {
            assertThatThrownBy(() -> DataScopes.reassign("x", new DataScopes.Owner(2, 10), () -> {}))
                    .isInstanceOf(DataScopeViolationException.class)
                    .hasMessageContaining("成员查询失败");
        } finally {
            MySqlHarness.asNobody();
        }
    }
}
