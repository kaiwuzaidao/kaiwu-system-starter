package com.kaiwu.datascope;

import com.kaiwu.starter.auth.model.ContextDataScope;

/**
 * 当前请求的数据范围视图，由 {@link DataScopes#current()} 返回。
 *
 * <p>只读。极少需要直接使用：典型用途是把 {@link #fingerprint()} 放进缓存键，避免不同范围的用户共用同一份缓存。</p>
 *
 * @param scope Context 中的范围；Context 没有 {@code ds} 时为 {@code null}，所有资源按仅本人
 */
public record DataScope(ContextDataScope scope) {

    /** @return 范围指纹，可作缓存键；没有 {@code ds} 时为固定值 {@code self} */
    public String fingerprint() {
        return scope == null ? "self" : scope.fingerprint();
    }

    /** @return 用户主部门；没有所属部门或没有 {@code ds} 时为 {@code null} */
    public Long primaryDeptId() {
        return scope == null ? null : scope.primaryDeptId();
    }

    /**
     * @param resourceCode 受控资源码
     * @return 该资源上的规则；缺失时为仅本人
     */
    public ContextDataScope.Rule rule(String resourceCode) {
        return scope == null ? ContextDataScope.Rule.SELF_ONLY : scope.rule(resourceCode);
    }
}
