package com.kaiwu.starter.agent;

/** 只暴露安全状态码，不携带远端错误正文、URL、令牌或底层异常消息。 */
public final class AgentRegistryException extends RuntimeException {
    private final int status;

    public AgentRegistryException(int status) {
        super("Agent registry request failed (status=" + status + ")");
        this.status = status;
    }

    public int status() {
        return status;
    }

    public boolean retryable() {
        return status == 0 || status == 429 || status >= 500;
    }
}
