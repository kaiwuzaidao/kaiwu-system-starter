package com.kaiwu.starter.auth.model;

import com.kaiwu.starter.auth.exception.LifecycleVerificationException;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 生命周期入口在路由重写或 URL 解码之前捕获的请求。
 * registeredProjectId 必须取自应用主库的路径项目登记，首次登记、未登记查询或台账重放时可为空，
 * 这些例外的领域准入和幂等仍由应用按已验证的 operationId/projectId 执行。
 */
public record LifecycleRequest(
        String method,
        String rawTarget,
        byte[] body,
        List<String> assertionHeaders,
        List<String> contentEncodingHeaders,
        String registeredProjectId) {
    public static final int MAX_BODY_BYTES = 64 * 1024;

    /** 在复制和哈希之前执行正文上限，快照输入，防止验证期间被修改。 */
    public LifecycleRequest {
        if (body == null) {
            throw new LifecycleVerificationException(HttpStatus.UNAUTHORIZED);
        }
        if (body.length > MAX_BODY_BYTES) {
            throw new LifecycleVerificationException(HttpStatus.PAYLOAD_TOO_LARGE);
        }
        body = body.clone();
        assertionHeaders = assertionHeaders == null ? List.of() : List.copyOf(assertionHeaders);
        contentEncodingHeaders = contentEncodingHeaders == null ? List.of() : List.copyOf(contentEncodingHeaders);
    }

    /** 向调用者返回正文副本，保留验证快照的不可变性。 */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /** 诊断输出不得带断言、项目或原始正文。 */
    @Override
    public String toString() {
        return "LifecycleRequest[请求快照]";
    }
}
