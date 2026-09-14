package com.kaiwu.starter.scheduler;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 启动即同步，之后周期刷新配置；同步失败时保留最后一次有效配置。
 */
public final class ProjectSchedulerLifecycle implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectSchedulerLifecycle.class);

    private final ProjectSchedulerRuntime runtime;
    private final ProjectSchedulerCoordinator coordinator;
    private final Duration syncInterval;
    private ScheduledExecutorService syncExecutor;
    private volatile boolean running;
    private long generation;

    public ProjectSchedulerLifecycle(
            ProjectSchedulerRuntime runtime, ProjectSchedulerCoordinator coordinator, Duration syncInterval) {
        this.runtime = runtime;
        this.coordinator = coordinator;
        this.syncInterval = syncInterval;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        long startGeneration = ++generation;
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                1,
                Thread.ofPlatform()
                        .daemon(true)
                        .name("kaiwu-scheduler-sync-", 0)
                        .factory(),
                new ThreadPoolExecutor.AbortPolicy());
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        syncExecutor = executor;
        // 返回的 ScheduledFuture 刻意丢弃：取消走 stop() 里的 shutdownNow()，
        // 没有第二个地方需要观察它。任务体已用 Throwable 兜底，不会异常逃逸。
        var unused = syncExecutor.scheduleWithFixedDelay(
                () -> synchronizeSafely(startGeneration), 0, Math.max(5, syncInterval.toSeconds()), TimeUnit.SECONDS);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        generation++;
        if (syncExecutor != null) {
            syncExecutor.shutdownNow();
            syncExecutor = null;
        }
        coordinator.close();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void synchronizeSafely(long startGeneration) {
        try {
            var jobs = runtime.synchronize();
            synchronized (this) {
                if (running && generation == startGeneration) {
                    coordinator.reconcile(jobs);
                }
            }
        } catch (Exception exception) {
            LOGGER.warn("同步 Kaiwu 项目定时任务失败，继续使用最后一次有效配置：{}", exception.getMessage());
        } catch (Throwable throwable) {
            // 兜住 Error：scheduleWithFixedDelay 的任务只要抛出去，调度就被永久取消且无人知晓，
            // 应用会一直跑在过期配置上。宁可记一条 error 继续排期，也不能让它静默停摆。
            LOGGER.error("同步 Kaiwu 项目定时任务时出现严重错误，本轮跳过", throwable);
        }
    }
}
