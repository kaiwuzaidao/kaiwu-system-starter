package com.kaiwu.starter;

import com.kaiwu.starter.auth.model.ContextDataScope;
import com.kaiwu.starter.auth.model.ProjectAdmission;
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
 * @param dataScope        数据范围（声明 {@code ds}）；缺失为 {@code null}，所有受控资源按仅本人
 * @param admission        多项目登记的已验证准入；单项目无登记时为 null
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
        String tokenId,
        ContextDataScope dataScope,
        ProjectAdmission admission) {

    public KaiwuContext {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        projectRoleCodes = projectRoleCodes == null ? Set.of() : Set.copyOf(projectRoleCodes);
    }

    /** 保留现有带数据范围的构造形态，单项目上下文不携带登记准入。 */
    public KaiwuContext(
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
            String tokenId,
            ContextDataScope dataScope) {
        this(
                userId,
                sessionId,
                projectId,
                projectCode,
                permissions,
                projectRoleCodes,
                authzVersion,
                audience,
                issuedAt,
                expiresAt,
                tokenId,
                dataScope,
                null);
    }

    /** 兼容不带数据范围的旧构造形态。 */
    public KaiwuContext(
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
        this(
                userId,
                sessionId,
                projectId,
                projectCode,
                permissions,
                projectRoleCodes,
                authzVersion,
                audience,
                issuedAt,
                expiresAt,
                tokenId,
                null);
    }

    public boolean hasPermission(String permission) {
        return permission != null && permissions.contains(permission);
    }
}
