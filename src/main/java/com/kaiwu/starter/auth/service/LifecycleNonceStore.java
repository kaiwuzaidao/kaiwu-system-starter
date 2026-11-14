package com.kaiwu.starter.auth.service;

import java.time.Instant;

/** 应用库提供的部署级防重放 SPI，所有副本必须共用同一权威存储。 */
@FunctionalInterface
public interface LifecycleNonceStore {
    /**
     * 原子占用 (issuer, deploymentId, jti)，首次写入须独立提交，禁止随领域事务回滚或释放。
     * retainUntil 为 exp+10 秒，覆盖 5 秒验证容差及最多 5 秒验证时钟落后。
     * 清理任务还须保证自身时钟不会使记录早于所有验证副本越过 exp+5 时删除。
     * 仅确定首次占用返回 CLAIMED，唯一冲突返回 REPLAY，未知提交结果返回 UNKNOWN 或抛异常。
     */
    ClaimResult claim(String issuer, String deploymentId, String jti, Instant retainUntil) throws Exception;

    /** 不确定结果与重复请求分别映射为 503 和 401。 */
    enum ClaimResult {
        CLAIMED,
        REPLAY,
        UNKNOWN
    }
}
