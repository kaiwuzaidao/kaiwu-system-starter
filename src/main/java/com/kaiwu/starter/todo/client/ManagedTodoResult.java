package com.kaiwu.starter.todo.client;

/** 可观察的投递或回执查询结果；不包含请求正文、凭据和服务端错误明文。 */
public record ManagedTodoResult(Disposition disposition, Receipt receipt, int httpStatus) {
    /** STORED 仅表示查到回执；只有投递的 APPLIED/DUPLICATE 可用于确认对应版本。 */
    public enum Disposition {
        APPLIED,
        DUPLICATE,
        SUPERSEDED,
        STORED,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE,
        NOT_CONFIGURED
    }

    /** 生产者、项目和投影键范围内的最小事实；调用方仍须核对本地摘要与版本。 */
    public record Receipt(long storedSequence, String storedState, boolean storedTerminal, String payloadDigest) {}
}
