package com.kaiwu.starter.auth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 生命周期验证失败的固定 HTTP 映射，不携带令牌、正文或底层存储异常。 */
public final class LifecycleVerificationException extends ResponseStatusException {
    /** 区分凭据拒绝、存储不确定和请求传输限制，领域处理不得继续执行。 */
    public LifecycleVerificationException(HttpStatus status) {
        super(
                status,
                switch (status) {
                    case SERVICE_UNAVAILABLE -> "生命周期重放存储不可用";
                    case UNSUPPORTED_MEDIA_TYPE -> "生命周期请求不支持内容编码";
                    case PAYLOAD_TOO_LARGE -> "生命周期请求正文过大";
                    default -> "生命周期断言无效";
                });
    }
}
