package com.kaiwu.starter.scheduler;

import java.time.Instant;

public record ProjectScheduledTaskContext(
        String projectId,
        String jobId,
        String executionId,
        Instant scheduledAt,
        String payload,
        ProjectSchedulerLease lease) {

    /**
     * 当前抢占代次的协作式栅栏 token。支持条件写入的下游应持久化并校验它。
     */
    public String fencingToken() {
        return lease.fencingToken();
    }

    public boolean isLeaseActive() {
        return lease.isActive();
    }

    public void assertLeaseActive() {
        lease.assertActive();
    }
}
