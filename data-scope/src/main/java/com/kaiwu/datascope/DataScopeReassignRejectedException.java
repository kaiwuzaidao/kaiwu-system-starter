package com.kaiwu.datascope;

/**
 * {@link DataScopes#reassign} 的新归属不合法：新归属人不是有效成员、新部门不是其所属部门，或人/部门为空或 0。
 *
 * <p>这是调用方可以修正的业务拒绝，业务服务应把它翻译成 4xx；其余 {@link DataScopeViolationException}
 * （没有 Context、成员查询故障、SQL 无法解析等）是服务端缺陷或依赖故障，保持 5xx。</p>
 */
public class DataScopeReassignRejectedException extends DataScopeViolationException {

    /** @param message 面向开发者的原因，不含部门 ID */
    public DataScopeReassignRejectedException(String message) {
        super(message);
    }
}
