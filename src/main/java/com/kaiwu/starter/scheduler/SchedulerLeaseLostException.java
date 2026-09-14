package com.kaiwu.starter.scheduler;

/**
 * 处理器主动检查执行租约时发现本实例已不再拥有该次执行。
 */
public final class SchedulerLeaseLostException extends IllegalStateException {

    public SchedulerLeaseLostException(String message) {
        super(message);
    }
}
