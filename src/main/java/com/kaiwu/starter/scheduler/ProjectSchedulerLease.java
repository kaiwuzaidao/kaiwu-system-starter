package com.kaiwu.starter.scheduler;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 暴露给任务处理器的协作式租约栅栏。
 *
 * <p>Java 无法安全终止任意业务副作用。处理器应在批次边界、外部写入前调用
 * {@link #assertActive()}，并把 {@link #fencingToken()} 传给支持幂等或条件写入的
 * 下游系统。</p>
 */
public final class ProjectSchedulerLease {

    private final String fencingToken;
    private final Thread handlerThread;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private volatile String lossReason;

    ProjectSchedulerLease(String fencingToken, Thread handlerThread) {
        this.fencingToken = fencingToken;
        this.handlerThread = handlerThread;
    }

    public String fencingToken() {
        return fencingToken;
    }

    public boolean isActive() {
        return active.get();
    }

    public void assertActive() {
        if (!isActive()) {
            throw new SchedulerLeaseLostException(lossReason == null ? "定时任务执行租约已失效" : lossReason);
        }
    }

    void lose(String reason) {
        if (active.compareAndSet(true, false)) {
            lossReason = reason;
            handlerThread.interrupt();
        }
    }

    void finish() {
        active.set(false);
    }
}
