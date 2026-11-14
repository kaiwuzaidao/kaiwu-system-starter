package com.kaiwu.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.kaiwu.starter.client.ServiceAddressResolver;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** 固化旧单服务站内信调用方的HTTP契约，后续C1–C5合入不得将尽力投递变成业务状态依赖。 */
class NotificationClientCompatibilityTest {
    /** 本地测试接收的旧协议请求；所有字段均为合成数据。 */
    private record Received(String method, String path, String token, String authorization, byte[] body) {}

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile int status;

    /** 每项创建独立回环HTTP端口，记录原始请求而不调用共享System。 */
    @BeforeEach
    void startServer() throws IOException {
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(new Received(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-Kaiwu-Delivery-Token"),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    /** 关闭本项端口，不留下后台接收任务。 */
    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** 为合成的旧项目delivery-token配置构造两秒有界客户端参数。 */
    private NotificationProperties properties(String address, String token) {
        var properties = new NotificationProperties();
        properties.setSystemBaseUrl(address);
        properties.setDeliveryToken(token);
        properties.setTimeoutSeconds(2);
        return properties;
    }

    /** 返回本轮回环服务地址，不继承部署或环境中的平台地址。 */
    private String address() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 旧四参数调用保持默认PROJECT类型、字符串长ID和原delivery-token头，不切换为Bearer。 */
    @Test
    void defaultOverloadKeepsLegacyWireAndStringIds() throws Exception {
        new NotificationClient(properties(address(), "baseline-test-token"), "legacy", ServiceAddressResolver.direct())
                .send("9007199254740993", "标题\"换行\n", "内容\\路径", "/orders/42");
        assertThat(received).hasSize(1);
        var call = received.getFirst();
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.path()).isEqualTo("/api/internal/notifications");
        assertThat(call.token()).isEqualTo("baseline-test-token");
        assertThat(call.authorization()).isNull();
        var actual = JsonMapper.builder().build().readValue(call.body(), Map.class);
        assertThat(actual)
                .isEqualTo(Map.of(
                        "projectCode",
                        "legacy",
                        "recipientUserId",
                        "9007199254740993",
                        "notificationType",
                        "PROJECT",
                        "title",
                        "标题\"换行\n",
                        "content",
                        "内容\\路径",
                        "linkUrl",
                        "/orders/42"));
    }

    /** 五参数调用保留显式类型，空正文及空链接继续省略，不能改成显式null字段。 */
    @Test
    void typedOverloadOmitsOptionalBlankFields() throws Exception {
        new NotificationClient(properties(address(), "baseline-test-token"), "legacy", ServiceAddressResolver.direct())
                .send("123", "TASK", "标题", null, " ");
        assertThat(received).hasSize(1);
        assertThat(JsonMapper.builder().build().readValue(received.getFirst().body(), Map.class))
                .isEqualTo(Map.of(
                        "projectCode", "legacy", "recipientUserId", "123", "notificationType", "TASK", "title", "标题"));
    }

    /** 缺地址、凭据或项目以及显式禁用均不投递，旧调用方无需为缺配置增加异常处理。 */
    @Test
    void incompleteConfigurationAndDisabledModeSendNothing() {
        var disabled = properties(address(), "baseline-test-token");
        disabled.setEnabled(false);
        assertThatCode(() -> {
                    new NotificationClient(
                                    properties(null, "baseline-test-token"), "legacy", ServiceAddressResolver.direct())
                            .send("123", "t", null, null);
                    new NotificationClient(properties(address(), null), "legacy", ServiceAddressResolver.direct())
                            .send("123", "t", null, null);
                    new NotificationClient(
                                    properties(address(), "baseline-test-token"), " ", ServiceAddressResolver.direct())
                            .send("123", "t", null, null);
                    new NotificationClient(disabled, "legacy", ServiceAddressResolver.direct())
                            .send("123", "t", null, null);
                })
                .doesNotThrowAnyException();
        assertThat(received).isEmpty();
    }

    /** System拒绝或暂不可用都只影响通知，不向旧业务调用方抛出异常或自动重发。 */
    @ParameterizedTest
    @ValueSource(ints = {403, 503})
    void rejectionAndTemporaryFailureNeverThrow(int responseStatus) {
        status = responseStatus;
        assertThatCode(() -> new NotificationClient(
                                properties(address(), "baseline-test-token"), "legacy", ServiceAddressResolver.direct())
                        .send("123", "标题", null, null))
                .doesNotThrowAnyException();
        assertThat(received).hasSize(1);
    }

    /** 无监听端口与非法地址都不得中断业务；仅测试回环地址，不触发实际平台请求。 */
    @Test
    void unreachableAndInvalidAddressRemainBestEffort() {
        assertThatCode(() -> {
                    new NotificationClient(
                                    properties("http://127.0.0.1:1", "baseline-test-token"),
                                    "legacy",
                                    ServiceAddressResolver.direct())
                            .send("123", "t", null, null);
                    new NotificationClient(
                                    properties("invalid-address", "baseline-test-token"),
                                    "legacy",
                                    ServiceAddressResolver.direct())
                            .send("123", "t", null, null);
                })
                .doesNotThrowAnyException();
        assertThat(received).isEmpty();
    }

    /** 服务发现没有可用实例时仍保留旧通知的尽力语义，不能把解析异常抛回业务事务。 */
    @Test
    void resolverFailureDoesNotEscapeIntoCaller() {
        ServiceAddressResolver unavailable = ignored -> {
            throw new IllegalStateException("no test instance");
        };
        assertThatCode(() -> new NotificationClient(
                                properties("lb://system", "baseline-test-token"), "legacy", unavailable)
                        .send("123", "t", null, null))
                .doesNotThrowAnyException();
        assertThat(received).isEmpty();
    }
}
