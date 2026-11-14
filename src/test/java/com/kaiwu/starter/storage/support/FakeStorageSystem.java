package com.kaiwu.starter.storage.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 模拟 System 的内部凭证接口：校验 Bearer 凭据，返回测试设定的 JSON。 */
public final class FakeStorageSystem implements AutoCloseable {

    /** 测试使用的项目服务凭据。 */
    public static final String TOKEN = "zsch_test-service-token";

    private final HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger calls = new AtomicInteger();

    /**
     * 在随机端口启动。
     *
     * @throws IOException 端口不可用时抛出
     */
    public FakeStorageSystem() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/storage/credentials", exchange -> {
            calls.incrementAndGet();
            boolean authorized =
                    ("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))
                            && "POST".equals(exchange.getRequestMethod());
            byte[] response = (authorized ? body.get() : "{\"code\":401}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(authorized ? status.get() : 401, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
    }

    /**
     * 返回服务地址。
     *
     * @return 形如 {@code http://127.0.0.1:port} 的地址
     */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * 设定下一次响应。
     *
     * @param httpStatus HTTP 状态码
     * @param json 响应体
     */
    public void respond(int httpStatus, String json) {
        status.set(httpStatus);
        body.set(json);
    }

    /**
     * 已收到的请求数。
     *
     * @return 请求数
     */
    public int calls() {
        return calls.get();
    }

    /**
     * 生成一个凭证 JSON 片段。
     *
     * @param ref 存储引用
     * @param name 存储名
     * @param mode 访问模式
     * @param prefix 前缀
     * @param endpoint 数据面地址
     * @param expiresAt ISO 时间
     * @param allowedTypes 允许类型的 JSON 数组文本
     * @return JSON 对象文本
     */
    public static String credential(
            String ref,
            String name,
            String mode,
            String prefix,
            String endpoint,
            String expiresAt,
            String allowedTypes) {
        return """
                {"storageRef":"%s","storageName":"%s","mode":"%s","providerType":"S3_COMPATIBLE_STS",
                 "endpoint":"%s","region":"us-east-1","bucket":"kaiwu-files","pathStyle":true,"prefix":"%s",
                 "maxObjectBytes":1048576,"allowedContentTypes":%s,"accessKeyId":"test",
                 "accessKeySecret":"test","securityToken":"session","expiresAt":"%s"}
                """
                .formatted(ref, name, mode, endpoint, prefix, allowedTypes, expiresAt);
    }

    /**
     * 包装成平台统一响应。
     *
     * @param credentials 逗号拼接的凭证 JSON
     * @return 响应体
     */
    public static String bundle(String credentials) {
        return "{\"code\":0,\"message\":\"ok\",\"data\":{\"projectCode\":\"demo\",\"credentials\":[" + credentials
                + "],\"unavailable\":[]}}";
    }

    /** 停止服务。 */
    @Override
    public void close() {
        server.stop(0);
    }
}
