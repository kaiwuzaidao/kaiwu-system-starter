package com.kaiwu.starter;

/**
 * 权限来源扩展点。
 *
 * <p>业务服务默认读取已签名 Context；System 可注册本地实现，从平台权限事实源读取。</p>
 */
@FunctionalInterface
public interface PermissionResolver {

    boolean hasPermission(KaiwuContext context, String permission);
}
