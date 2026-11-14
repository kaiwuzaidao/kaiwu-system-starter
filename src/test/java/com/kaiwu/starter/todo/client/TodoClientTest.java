package com.kaiwu.starter.todo.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.kaiwu.starter.NotificationProperties;
import com.kaiwu.starter.client.ServiceAddressResolver;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 待办客户端的投递格式与「绝不阻断业务」保证。
 *
 * <p>用真实的本地 HTTP 服务端收请求，断言路径、凭据头与 JSON 内容；失败场景都要求不抛异常。
 */
class TodoClientTest {

    /** 服务端收到的一次请求。 */
    private record Received(String path, String token, String body) {}

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile int status = 200;

    /**
     * 启动本地服务端，记录所有请求。
     *
     * @throws IOException 端口绑定失败
     */
    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(new Received(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-Kaiwu-Delivery-Token"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] body = "{\"code\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    /** 停止本地服务端。 */
    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** 构造投递配置：指定 System 地址与凭据，超时缩短以便失败用例快速返回。 */
    private NotificationProperties properties(String url, String token) {
        NotificationProperties properties = new NotificationProperties();
        properties.setSystemBaseUrl(url);
        properties.setDeliveryToken(token);
        properties.setTimeoutSeconds(2);
        return properties;
    }

    /** 构造指向本地服务端的客户端，项目编码固定为 flow。 */
    private TodoClient client() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new TodoClient(properties(url, "secret"), "flow", ServiceAddressResolver.direct());
    }

    /** 打开待办：投递到打开端点，带服务凭据，字段齐全，截止时间是 ISO 本地时间。 */
    @Test
    void openPostsTheFullPayloadWithTheDeliveryToken() {
        client().open(
                        "501",
                        "approval:1",
                        "待审批",
                        "请尽快处理",
                        "/workspace/601",
                        LocalDateTime.parse("2026-10-24T18:30:00"));

        assertThat(received).hasSize(1);
        Received call = received.get(0);
        assertThat(call.path()).isEqualTo("/api/internal/todos");
        assertThat(call.token()).isEqualTo("secret");
        assertThat(call.body())
                .contains("\"projectCode\":\"flow\"")
                .contains("\"bizKey\":\"approval:1\"")
                .contains("\"recipientUserId\":\"501\"")
                .contains("\"title\":\"待审批\"")
                .contains("\"content\":\"请尽快处理\"")
                .contains("\"linkUrl\":\"/workspace/601\"")
                .contains("\"dueAt\":\"2026-10-24T18:30:00\"");
    }

    /** 简化形式不带正文、链接与截止时间，请求里也不应出现这些字段（缺省由平台补）。 */
    @Test
    void simpleOpenOmitsOptionalFields() {
        client().open("501", "approval:1", "待审批");

        assertThat(received.get(0).body())
                .doesNotContain("content")
                .doesNotContain("linkUrl")
                .doesNotContain("dueAt");
    }

    /** 标题与业务键里的引号、反斜杠、换行必须转义，否则会破坏 JSON 甚至注入字段。 */
    @Test
    void escapesSpecialCharactersInText() {
        client().open("501", "k", "他说\"好\"\\\n下一行\t制表");

        assertThat(received.get(0).body()).contains("\"title\":\"他说\\\"好\\\"\\\\\\n下一行\\t制表\"");
    }

    /** 不带处理人关闭：请求里没有 recipientUserId（关闭所有人），结果为 DONE。 */
    @Test
    void closeWithoutRecipientTargetsEveryone() {
        client().close("approval:1");

        Received call = received.get(0);
        assertThat(call.path()).isEqualTo("/api/internal/todos/close");
        assertThat(call.body())
                .contains("\"bizKey\":\"approval:1\"")
                .contains("\"result\":\"DONE\"")
                .doesNotContain("recipientUserId");
    }

    /** 带处理人关闭：只关闭那一个人（会签）。 */
    @Test
    void closeWithRecipientTargetsOnlyThatPerson() {
        client().close("approval:1", "501");

        assertThat(received.get(0).body())
                .contains("\"recipientUserId\":\"501\"")
                .contains("\"result\":\"DONE\"");
    }

    /** 取消用 CANCELLED 表达「事项不办了」，与办理完成区分，按人或按键两种范围。 */
    @Test
    void cancelUsesTheCancelledResult() {
        client().cancel("approval:1");
        client().cancel("approval:2", "502");

        assertThat(received.get(0).body()).contains("\"result\":\"CANCELLED\"").doesNotContain("recipientUserId");
        assertThat(received.get(1).body()).contains("\"result\":\"CANCELLED\"").contains("\"recipientUserId\":\"502\"");
    }

    /** 缺地址、缺凭据、被关闭或缺项目编码时一律静默跳过，不发任何请求也不抛异常。 */
    @Test
    void doesNothingWhenNotFullyConfigured() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        NotificationProperties disabled = properties(url, "secret");
        disabled.setEnabled(false);

        assertThatCode(() -> {
                    new TodoClient(properties(null, "secret"), "flow", ServiceAddressResolver.direct())
                            .open("1", "k", "t");
                    new TodoClient(properties(url, null), "flow", ServiceAddressResolver.direct()).open("1", "k", "t");
                    new TodoClient(disabled, "flow", ServiceAddressResolver.direct()).open("1", "k", "t");
                    new TodoClient(properties(url, "secret"), " ", ServiceAddressResolver.direct()).close("k");
                })
                .doesNotThrowAnyException();
        assertThat(received).isEmpty();
    }

    /** 平台拒绝（如收件人不是成员）时只记日志，不抛异常。 */
    @Test
    void serverRejectionNeverThrows() {
        status = 403;

        assertThatCode(() -> client().open("999", "k", "t")).doesNotThrowAnyException();
        assertThat(received).hasSize(1);
    }

    /** 平台不可达或地址写错时同样不抛异常，不能把业务事务拖垮。 */
    @Test
    void unreachableOrInvalidAddressNeverThrows() {
        assertThatCode(() -> {
                    new TodoClient(properties("http://127.0.0.1:1", "secret"), "flow", ServiceAddressResolver.direct())
                            .open("1", "k", "t");
                    new TodoClient(properties("not a url", "secret"), "flow", ServiceAddressResolver.direct())
                            .close("k");
                })
                .doesNotThrowAnyException();
    }
}
