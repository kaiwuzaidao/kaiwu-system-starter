package com.kaiwu.starter.scheduler;

/**
 * 业务项目显式注册的定时任务处理器。
 *
 * <p>taskType 是 System 可配置的唯一执行入口；不得使用类名、脚本或表达式替代。</p>
 */
public interface ProjectScheduledTaskHandler {

    String taskType();

    default String taskName() {
        return taskType();
    }

    void execute(ProjectScheduledTaskContext context) throws Exception;
}
