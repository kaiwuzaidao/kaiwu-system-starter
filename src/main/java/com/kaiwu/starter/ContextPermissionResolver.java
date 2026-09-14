package com.kaiwu.starter;

/**
 * 业务服务默认权限解析器：只信任已验证 Context 内的权限集合。
 */
public final class ContextPermissionResolver implements PermissionResolver {

    @Override
    public boolean hasPermission(KaiwuContext context, String permission) {
        return context != null && context.hasPermission(permission);
    }
}
