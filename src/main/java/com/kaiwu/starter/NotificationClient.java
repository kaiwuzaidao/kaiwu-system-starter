package com.kaiwu.starter;

import com.kaiwu.starter.client.ServiceAddress;
import com.kaiwu.starter.client.ServiceAddressResolver;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 业务服务向 Kaiwu System 投递站内信（ADR 0007）。
 *
 * <p>使用约定：
 * <ul>
 *   <li>由业务代码在明确的业务事件后<b>显式调用</b>，不得挂在请求拦截器上，
 *       也不得随每个业务请求触发。</li>
 *   <li>站内信是尽力而为的通知：投递失败只记录日志，<b>绝不抛出异常、绝不阻断业务事务</b>。
 *       业务流程不得依赖它做状态流转。</li>
 *   <li>收件人必须是本项目的有效成员，否则 System 会拒绝投递。</li>
 * </ul>
 *
 * <pre>{@code
 * notificationClient.send(order.getOwnerUserId(),
 *         "订单已超时", "订单 " + order.getOrderNo() + " 超过 24 小时未支付",
 *         "/order/" + order.getId());
 * }</pre>
 */
public class NotificationClient {

    private static final Logger log = LoggerFactory.getLogger(NotificationClient.class);
    private static final String DELIVERY_TOKEN_HEADER = "X-Kaiwu-Delivery-Token";
    private static final String DEFAULT_TYPE = "PROJECT";

    private final boolean enabled;
    private final ServiceAddress systemAddress;
    private final ServiceAddressResolver addressResolver;
    private final String deliveryToken;
    private final long timeoutSeconds;
    private final String projectCode;
    private final HttpClient httpClient;

    public NotificationClient(
            NotificationProperties properties, String projectCode, ServiceAddressResolver addressResolver) {
        this.enabled = properties.isEnabled();
        // 地址非法时不能让站内信拖垮启动：本客户端整体是尽力而为的，
        // 未配置与配置错误一样退化为「不投递」，但配置错误要留下日志。
        this.systemAddress = parseQuietly(properties.getSystemBaseUrl());
        this.addressResolver = addressResolver;
        this.deliveryToken = properties.getDeliveryToken();
        this.timeoutSeconds = Math.max(1, properties.getTimeoutSeconds());
        this.projectCode = projectCode;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    private static ServiceAddress parseQuietly(String configuredUrl) {
        if (configuredUrl == null || configuredUrl.isBlank()) {
            return null;
        }
        try {
            return ServiceAddress.parse(configuredUrl);
        } catch (RuntimeException exception) {
            log.warn("站内信地址无法解析，将不投递：{}", exception.getMessage());
            return null;
        }
    }

    /**
     * 向本项目的某个成员投递站内信。
     *
     * @param recipientUserId 收件人平台用户 ID，必须是本项目有效成员
     * @param title           标题，不超过 200 字
     * @param content         正文，可空，不超过 2000 字
     * @param linkUrl         点击跳转的站内相对路径，可空
     */
    public void send(String recipientUserId, String title, String content, String linkUrl) {
        send(recipientUserId, DEFAULT_TYPE, title, content, linkUrl);
    }

    /**
     * 与 {@link #send(String, String, String, String)} 相同，但可指定消息分类。
     *
     * @param type 受管字典 {@code notification.type} 的取值，如 PROJECT、TASK
     */
    public void send(String recipientUserId, String type, String title, String content, String linkUrl) {
        if (!isReady()) {
            log.debug("站内信未配置，跳过投递：title={}", title);
            return;
        }
        try {
            String body = buildBody(recipientUserId, type, title, content, linkUrl);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + "/api/internal/notifications"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header(DELIVERY_TOKEN_HEADER, deliveryToken)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn(
                        "站内信投递被拒绝：status={}, title={}, responseBytes={}",
                        response.statusCode(),
                        title,
                        response.body() == null ? 0 : response.body().getBytes(StandardCharsets.UTF_8).length);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("站内信投递被中断：title={}", title);
        } catch (Exception exception) {
            // 通知失败绝不阻断业务，只留日志。
            log.warn("站内信投递失败，已忽略：title={}", title, exception);
        }
    }

    private boolean isReady() {
        return enabled && systemAddress != null && hasText(deliveryToken) && hasText(projectCode);
    }

    /**
     * 解析本次投递要用的地址。{@code lb://} 每次重新选实例——缓存下来等于把负载均衡
     * 退化成固定实例，实例下线后会一直打到死节点。
     */
    private String baseUrl() {
        return addressResolver.resolve(systemAddress);
    }

    private String buildBody(String recipientUserId, String type, String title, String content, String linkUrl) {
        StringBuilder json = new StringBuilder(256);
        json.append('{')
                .append("\"projectCode\":\"")
                .append(escape(projectCode))
                .append("\",")
                .append("\"recipientUserId\":\"")
                .append(escape(recipientUserId))
                .append("\",")
                .append("\"notificationType\":\"")
                .append(escape(hasText(type) ? type : DEFAULT_TYPE))
                .append("\",")
                .append("\"title\":\"")
                .append(escape(title))
                .append('"');
        if (hasText(content)) {
            json.append(",\"content\":\"").append(escape(content)).append('"');
        }
        if (hasText(linkUrl)) {
            json.append(",\"linkUrl\":\"").append(escape(linkUrl)).append('"');
        }
        return json.append('}').toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 最小 JSON 字符串转义，避免为一次投递引入额外序列化依赖。 */
    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
