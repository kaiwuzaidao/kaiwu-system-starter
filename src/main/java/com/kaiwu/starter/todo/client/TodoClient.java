package com.kaiwu.starter.todo.client;

import com.kaiwu.starter.NotificationProperties;
import com.kaiwu.starter.client.ServiceAddress;
import com.kaiwu.starter.client.ServiceAddressResolver;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 业务服务向 Kaiwu System 投递统一待办（ADR 0034）。
 *
 * <p>待办与站内信的区别：站内信是不可变事实，待办的状态由<b>业务服务自己维护</b>——事情发生时
 * {@code open}，办完时 {@code close}，处理人在平台上看到的待办就会自动出现和消失。
 * 平台不允许处理人手动把待办标记为完成，避免平台状态与业务事实分叉。
 *
 * <p>使用约定：
 * <ul>
 *   <li>由业务代码在明确的业务事件后<b>显式调用</b>，不得挂在请求拦截器上，也不得随每个业务请求触发。</li>
 *   <li>投递是尽力而为：失败只记录日志，<b>绝不抛出异常、绝不阻断业务事务</b>。业务流程不得依赖待办做状态流转，
 *       漏发「关闭」会由平台每日清理兜底，但在那之前处理人会看到一条陈旧的待办。</li>
 *   <li>{@code bizKey} 是业务事项的唯一键（如 {@code approval:2026-0001}），由业务服务自行约定。
 *       同一事项的多位处理人共用同一个键；同一个键重复 {@code open} 是幂等的。</li>
 *   <li>打开时处理人必须是本项目的有效成员，否则 System 会拒绝；关闭不要求。</li>
 *   <li>复用站内信的 {@code kaiwu.starter.notification.*} 配置（System 地址与投递凭据），缺任一项时静默跳过。</li>
 * </ul>
 *
 * <pre>{@code
 * // 提交审批：通知两位审批人（或签——任何一人办完，两个人的待办一起消失）
 * for (String approver : approvers) {
 *     todoClient.open(approver, "approval:" + order.getNo(), "待审批：" + order.getTitle());
 * }
 * // 有人审批通过后：关闭该事项下所有人的待办
 * todoClient.close("approval:" + order.getNo());
 *
 * // 会签：每人各办各的，只关闭当前审批人自己的
 * todoClient.close("approval:" + order.getNo(), currentApproverId);
 * }</pre>
 */
public class TodoClient {

    private static final Logger log = LoggerFactory.getLogger(TodoClient.class);
    private static final String DELIVERY_TOKEN_HEADER = "X-Kaiwu-Delivery-Token";
    private static final String DONE = "DONE";
    private static final String CANCELLED = "CANCELLED";

    private final boolean enabled;
    private final ServiceAddress systemAddress;
    private final ServiceAddressResolver addressResolver;
    private final String deliveryToken;
    private final long timeoutSeconds;
    private final String projectCode;
    private final HttpClient httpClient;

    /**
     * 创建客户端。
     *
     * @param properties 站内信与待办共用的投递配置：是否启用、System 地址、投递凭据与超时
     * @param projectCode 本项目的编码，作为待办的来源项目
     * @param addressResolver 目标地址解析器，{@code lb://} 每次投递重新选实例
     */
    public TodoClient(NotificationProperties properties, String projectCode, ServiceAddressResolver addressResolver) {
        this.enabled = properties.isEnabled();
        // 地址非法时不能让待办拖垮启动：本客户端整体是尽力而为的，
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

    /** 解析 System 地址；非法时记录日志并返回 null，使客户端整体退化为不投递。 */
    private static ServiceAddress parseQuietly(String configuredUrl) {
        if (configuredUrl == null || configuredUrl.isBlank()) {
            return null;
        }
        try {
            return ServiceAddress.parse(configuredUrl);
        } catch (RuntimeException exception) {
            log.warn("待办投递地址无法解析，将不投递：{}", exception.getMessage());
            return null;
        }
    }

    /**
     * 为一位处理人打开一条待办（简化形式：无正文、无截止时间，点击进入本项目后台）。
     *
     * @param recipientUserId 处理人的平台用户 ID，必须是本项目有效成员
     * @param bizKey 业务事项唯一键
     * @param title 标题，不超过 200 字
     */
    public void open(String recipientUserId, String bizKey, String title) {
        open(recipientUserId, bizKey, title, null, null, null);
    }

    /**
     * 为一位处理人打开（新建或重新打开）一条待办。同一个业务键重复调用只会更新内容。
     *
     * @param recipientUserId 处理人的平台用户 ID，必须是本项目有效成员
     * @param bizKey 业务事项唯一键，不超过 128 个字符，只能包含字母、数字与 {@code : _ . / # -}
     * @param title 标题，不超过 200 字
     * @param content 正文，可空，不超过 2000 字
     * @param linkUrl 点击跳转的平台内路径，可空；缺省时进入本项目的后台入口
     * @param dueAt 截止时间，可空；使用与 System 相同时区的本地时间，过期后待办会标为逾期
     */
    public void open(
            String recipientUserId, String bizKey, String title, String content, String linkUrl, LocalDateTime dueAt) {
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        appendString(json, "projectCode", projectCode, false);
        appendString(json, "bizKey", bizKey, true);
        appendString(json, "recipientUserId", recipientUserId, true);
        appendString(json, "title", title, true);
        if (hasText(content)) {
            appendString(json, "content", content, true);
        }
        if (hasText(linkUrl)) {
            appendString(json, "linkUrl", linkUrl, true);
        }
        if (dueAt != null) {
            appendString(json, "dueAt", DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(dueAt), true);
        }
        post("/api/internal/todos", json.append('}').toString(), "打开", bizKey);
    }

    /**
     * 办理完成：关闭该业务键下<b>所有处理人</b>的待办（或签：一人办完，其他人的同时消失）。幂等。
     *
     * @param bizKey 业务事项唯一键，与打开时一致
     */
    public void close(String bizKey) {
        closeWith(bizKey, null, DONE);
    }

    /**
     * 办理完成：只关闭<b>某一位处理人</b>的待办（会签：每人各办各的）。幂等。
     *
     * @param bizKey 业务事项唯一键，与打开时一致
     * @param recipientUserId 只关闭该处理人的待办；他人的保持未处理
     */
    public void close(String bizKey, String recipientUserId) {
        closeWith(bizKey, recipientUserId, DONE);
    }

    /**
     * 事项取消：把该业务键下所有处理人的待办标为已取消（而不是办理完成）。幂等。
     *
     * @param bizKey 业务事项唯一键，与打开时一致
     */
    public void cancel(String bizKey) {
        closeWith(bizKey, null, CANCELLED);
    }

    /**
     * 事项取消：只把某一位处理人的待办标为已取消。幂等。
     *
     * @param bizKey 业务事项唯一键，与打开时一致
     * @param recipientUserId 只取消该处理人的待办
     */
    public void cancel(String bizKey, String recipientUserId) {
        closeWith(bizKey, recipientUserId, CANCELLED);
    }

    /** 组装并投递关闭请求；关闭范围由 recipientUserId 是否为空决定。 */
    private void closeWith(String bizKey, String recipientUserId, String result) {
        StringBuilder json = new StringBuilder(160);
        json.append('{');
        appendString(json, "projectCode", projectCode, false);
        appendString(json, "bizKey", bizKey, true);
        if (hasText(recipientUserId)) {
            appendString(json, "recipientUserId", recipientUserId, true);
        }
        appendString(json, "result", result, true);
        post("/api/internal/todos/close", json.append('}').toString(), "关闭", bizKey);
    }

    /** 判断是否具备投递条件：已启用、地址与凭据齐全、本项目编码已知。 */
    private boolean isReady() {
        return enabled && systemAddress != null && hasText(deliveryToken) && hasText(projectCode);
    }

    /**
     * 向 System 投递一次请求。任何失败都只记录日志：待办是尽力而为的投影，不是业务一致性的一部分。
     *
     * @param path 投递端点路径
     * @param body 请求 JSON
     * @param action 日志用的动作名
     * @param bizKey 日志用的业务键
     */
    private void post(String path, String body, String action, String bizKey) {
        if (!isReady()) {
            log.debug("待办未配置，跳过{}：bizKey={}", action, bizKey);
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    // lb:// 每次重新选实例——缓存下来等于把负载均衡退化成固定实例。
                    .uri(URI.create(addressResolver.resolve(systemAddress) + path))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header(DELIVERY_TOKEN_HEADER, deliveryToken)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn(
                        "待办{}被拒绝：status={}, bizKey={}, responseBytes={}",
                        action,
                        response.statusCode(),
                        bizKey,
                        response.body() == null ? 0 : response.body().getBytes(StandardCharsets.UTF_8).length);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("待办{}被中断：bizKey={}", action, bizKey);
        } catch (Exception exception) {
            // 待办投递失败绝不阻断业务，只留日志。
            log.warn("待办{}失败，已忽略：bizKey={}", action, bizKey, exception);
        }
    }

    /** 追加一个字符串字段；非首个字段前补逗号。 */
    private static void appendString(StringBuilder json, String name, String value, boolean comma) {
        if (comma) {
            json.append(',');
        }
        json.append('"').append(name).append("\":\"").append(escape(value)).append('"');
    }

    /** 文本是否非空白。 */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 最小 JSON 字符串转义，避免为一次投递引入额外序列化依赖。 */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
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
