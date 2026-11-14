package com.kaiwu.starter.members.client;

/** 成员服务不可用、拒绝或返回非法数据；调用方必须保持解析等待。 */
public final class MemberQueryException extends RuntimeException {
    private final int httpStatus;
    /** 创建不包含凭据及成员资料的失败说明。 */
    public MemberQueryException(String message) {
        this(message, 0);
    }
    /** 状态码为零表示网络错误或不可信响应；不保留响应体。 */
    public MemberQueryException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }
    /** 返回服务端 HTTP 状态，供调用方区分超限、鉴权和依赖故障。 */
    public int httpStatus() {
        return httpStatus;
    }
}
