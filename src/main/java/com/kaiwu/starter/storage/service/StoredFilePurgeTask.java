package com.kaiwu.starter.storage.service;

import com.kaiwu.starter.scheduler.ProjectScheduledTaskContext;
import com.kaiwu.starter.scheduler.ProjectScheduledTaskHandler;
import java.time.Duration;

/**
 * 在平台调度里注册的文件清理任务（ADR 0041）。System 的定时任务页面按 {@link #TASK_TYPE} 配置执行周期，
 * 建议每小时一次；未配置时孤儿文件只是不被回收，不影响业务。
 */
public class StoredFilePurgeTask implements ProjectScheduledTaskHandler {

    /** 定时任务类型，System 配置任务时填写。 */
    public static final String TASK_TYPE = "kaiwu.file.purge";

    private final StoredFileService service;
    private final Duration pendingTtl;
    private final Duration deletedRetention;

    /**
     * 创建清理任务。
     *
     * @param service 文件服务
     * @param pendingTtl 待确认记录的最长保留时间
     * @param deletedRetention 软删除记录的保留时间
     */
    public StoredFilePurgeTask(StoredFileService service, Duration pendingTtl, Duration deletedRetention) {
        this.service = service;
        this.pendingTtl = pendingTtl;
        this.deletedRetention = deletedRetention;
    }

    /** 返回定时任务类型。 */
    @Override
    public String taskType() {
        return TASK_TYPE;
    }

    /** 返回定时任务的展示名称。 */
    @Override
    public String taskName() {
        return "清理孤儿与过期删除的文件";
    }

    /** 执行一轮清理。 */
    @Override
    public void execute(ProjectScheduledTaskContext context) {
        service.purgeExpired(pendingTtl, deletedRetention);
    }
}
