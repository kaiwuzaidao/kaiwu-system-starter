package com.kaiwu.starter.auth.model;

import java.time.Instant;

/** 完成签名、请求绑定和持久 nonce 占用后返回的身份，不能替代领域幂等和状态守卫。 */
public record VerifiedLifecycleAssertion(
        String operationId,
        String projectId,
        String projectCode,
        String appCode,
        String deploymentId,
        String scope,
        String kid,
        Instant expiresAt) {}
