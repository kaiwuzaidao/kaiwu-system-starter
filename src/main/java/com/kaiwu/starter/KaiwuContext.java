package com.kaiwu.starter;

import java.time.Instant;
import java.util.Set;

/**
 * Gateway 已验证并签发的请求上下文。
 *
 * @param userId           全局用户 ID，始终使用字符串
 * @param sessionId        在线会话 ID
 * @param projectId        项目 ID；平台请求可为空
 * @param projectCode      项目编码；平台请求可为空
 * @param permissions      当前项目权限集合
 * @param projectRoleCodes 当前项目角色编码集合
 * @param authzVersion     权限快照摘要
 * @param audience         唯一目标服务
 * @param issuedAt         签发时间
 * @param expiresAt        过期时间
 * @param tokenId          Context 唯一 ID
 */
public record KaiwuContext(
        String userId,
        String sessionId,
        String projectId,
        String projectCode,
        Set<String> permissions,
        Set<String> projectRoleCodes,
        String authzVersion,
        String audience,
        Instant issuedAt,
        Instant expiresAt,
        String tokenId) {

    public KaiwuContext {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        projectRoleCodes = projectRoleCodes == null ? Set.of() : Set.copyOf(projectRoleCodes);
    }

    public boolean hasPermission(String permission) {
        return permission != null && permissions.contains(permission);
    }
}
