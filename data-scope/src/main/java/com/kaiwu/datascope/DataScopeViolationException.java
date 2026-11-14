package com.kaiwu.datascope;

/**
 * 数据范围被违反或无法确认时抛出：没有 Context 却触达受控表、SQL 无法解析、禁止的语句形态、归属校验失败等。
 *
 * <p>一律失败关闭——宁可让请求报错，也不放行一条可能越权的语句。消息不得包含部门 ID 等组织结构信息。</p>
 */
public class DataScopeViolationException extends RuntimeException {

    /** @param message 面向开发者的原因，不含部门 ID */
    public DataScopeViolationException(String message) {
        super(message);
    }

    /**
     * @param message 面向开发者的原因，不含部门 ID
     * @param cause   底层原因
     */
    public DataScopeViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
