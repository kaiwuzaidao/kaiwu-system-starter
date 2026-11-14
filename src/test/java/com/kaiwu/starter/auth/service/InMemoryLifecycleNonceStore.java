package com.kaiwu.starter.auth.service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 仅测试使用的原子占用桩，没有生产 Bean 或自动回退路径，也不自动删除 nonce。 */
final class InMemoryLifecycleNonceStore implements LifecycleNonceStore {
    private final Map<Key, Instant> claimed = new ConcurrentHashMap<>();

    /** 三元组使用独立字段，避免字符串拼接造成碰撞。 */
    private record Key(String issuer, String deploymentId, String jti) {}

    /** 模拟已独立提交的唯一约束，同一键跨线程只有一次成功。 */
    @Override
    public ClaimResult claim(String issuer, String deploymentId, String jti, Instant retainUntil) {
        return claimed.putIfAbsent(new Key(issuer, deploymentId, jti), retainUntil) == null
                ? ClaimResult.CLAIMED
                : ClaimResult.REPLAY;
    }

    /** 测试清理时限是否覆盖全部验证端容差。 */
    Instant retention(String deploymentId, String jti) {
        return claimed.get(new Key("kaiwu-system", deploymentId, jti));
    }

    /** 无效断言不能触及防重放存储。 */
    int size() {
        return claimed.size();
    }
}
