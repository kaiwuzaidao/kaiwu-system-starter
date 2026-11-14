package com.kaiwu.starter.todo.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaiwu.starter.KaiwuStarterAutoConfiguration;
import com.kaiwu.starter.KaiwuStarterProperties;
import com.kaiwu.starter.NotificationProperties;
import com.kaiwu.starter.client.ServiceAddressResolver;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 待办客户端的装配接线：来源项目取自 Starter 的项目编码，System 地址与投递凭据复用站内信配置。
 *
 * <p>不启动完整的 Starter 上下文——它还要求 Gateway 公钥等与待办无关的必需配置，这里只验证本 Bean 的接线。
 */
class TodoClientAutoConfigurationTest {

    /**
     * 经装配方法创建的客户端，投递时使用 Starter 配置的项目编码与站内信配置的凭据。
     *
     * @throws IOException 本地服务端启动失败
     */
    @Test
    void wiresProjectCodeFromStarterAndCredentialsFromNotificationProperties() throws IOException {
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Kaiwu-Delivery-Token"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            NotificationProperties notification = new NotificationProperties();
            notification.setSystemBaseUrl(
                    "http://127.0.0.1:" + server.getAddress().getPort());
            notification.setDeliveryToken("shared-token");
            KaiwuStarterProperties starter = new KaiwuStarterProperties();
            starter.setProjectCode("flow");

            TodoClient client = new KaiwuStarterAutoConfiguration()
                    .kaiwuTodoClient(notification, starter, ServiceAddressResolver.direct());
            client.open("501", "approval:1", "待审批");

            assertThat(token.get()).isEqualTo("shared-token");
            assertThat(body.get()).contains("\"projectCode\":\"flow\"");
        } finally {
            server.stop(0);
        }
    }
}
