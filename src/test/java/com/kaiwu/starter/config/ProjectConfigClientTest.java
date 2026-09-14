package com.kaiwu.starter.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 配置读取必须永远给出确定值：平台不可达时业务不能因此崩，也不能读到空串当真值。
 */
class ProjectConfigClientTest {

    @Test
    void returnsFallbackWhenNotConfigured() {
        ProjectConfigProperties properties = new ProjectConfigProperties();
        // 未配置 systemBaseUrl / credential：整体降级，不应尝试任何网络调用。
        try (ProjectConfigClient client = new ProjectConfigClient(properties)) {
            client.start();

            assertThat(client.getString("any.key", "fallback")).isEqualTo("fallback");
            assertThat(client.getInt("any.key", 7)).isEqualTo(7);
            assertThat(client.getBoolean("any.key", true)).isTrue();
            assertThat(client.snapshot()).isEmpty();
        }
    }

    @Test
    void loadsSnapshotAndAppliesTypedFallbacks() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = serve(
                """
                {"code":0,"message":"ok","data":[
                  {"key":"order.batchSize","value":"200","valueType":"NUMBER"},
                  {"key":"order.enabled","value":"true","valueType":"BOOLEAN"},
                  {"key":"order.notice","value":"限时活动","valueType":"STRING"},
                  {"key":"order.broken","value":"abc","valueType":"NUMBER"},
                  {"key":"order.empty","value":"","valueType":"STRING"}
                ]}""",
                authorization,
                new AtomicInteger());
        try (ProjectConfigClient client =
                new ProjectConfigClient(properties(server.getAddress().getPort()))) {
            client.refresh();

            assertThat(client.getInt("order.batchSize", 10)).isEqualTo(200);
            assertThat(client.getBoolean("order.enabled", false)).isTrue();
            assertThat(client.getString("order.notice", "-")).isEqualTo("限时活动");
            // 值存在但不是合法数字时必须退回 fallback，不能抛异常打断业务。
            assertThat(client.getInt("order.broken", 10)).isEqualTo(10);
            // 空串视为未配置，否则会把空值当成有效配置用出去。
            assertThat(client.getString("order.empty", "默认")).isEqualTo("默认");
            assertThat(client.getString("order.missing", "默认")).isEqualTo("默认");
            // 凭据必须以 Bearer 形式送出，平台侧据此确定项目。
            assertThat(authorization.get()).isEqualTo("Bearer zsch_1_secret");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsPreviousSnapshotWhenPlatformFails() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = serve(
                """
                {"code":0,"message":"ok","data":[
                  {"key":"order.batchSize","value":"200","valueType":"NUMBER"}
                ]}""",
                new AtomicReference<>(),
                requests);
        try (ProjectConfigClient client =
                new ProjectConfigClient(properties(server.getAddress().getPort()))) {
            client.refresh();
            assertThat(client.getInt("order.batchSize", 10)).isEqualTo(200);

            server.stop(0);
            client.refresh();

            // 平台挂掉后沿用上一次快照：旧值比无值可用，且不得抛异常。
            assertThat(client.getInt("order.batchSize", 10)).isEqualTo(200);
        }
    }

    private static ProjectConfigProperties properties(int port) {
        ProjectConfigProperties properties = new ProjectConfigProperties();
        properties.setSystemBaseUrl("http://127.0.0.1:" + port);
        properties.setCredential("zsch_1_secret");
        properties.setTimeoutSeconds(2);
        return properties;
    }

    private static HttpServer serve(String body, AtomicReference<String> authorization, AtomicInteger requests)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/configs", exchange -> {
            requests.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        return server;
    }
}
