package com.kaiwu.starter.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 与具体 Cron 调度器解耦的执行内核，负责项目隔离、Handler allowlist 与中央抢占。
 */
public final class ProjectSchedulerRuntime implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectSchedulerRuntime.class);
    private static final Duration DEFAULT_RENEW_INTERVAL = Duration.ofMinutes(1);
    private static final int DEFAULT_MAX_CONSECUTIVE_RENEW_FAILURES = 3;
    private final String projectId;
    private final String instanceId;
    private final SchedulerControlPlane controlPlane;
    private final Map<String, ProjectScheduledTaskHandler> handlers;
    private final Duration renewInterval;
    private final int maxConsecutiveRenewFailures;
    private final ScheduledExecutorService leaseExecutor;
    private final Set<ExecutionKey> runningExecutions = ConcurrentHashMap.newKeySet();
    private final Set<ProjectSchedulerLease> activeLeases = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ProjectSchedulerRuntime(
            String projectId,
            String instanceId,
            SchedulerControlPlane controlPlane,
            List<ProjectScheduledTaskHandler> handlers) {
        this(
                projectId,
                instanceId,
                controlPlane,
                handlers,
                DEFAULT_RENEW_INTERVAL,
                DEFAULT_MAX_CONSECUTIVE_RENEW_FAILURES);
    }

    public ProjectSchedulerRuntime(
            String projectId,
            String instanceId,
            SchedulerControlPlane controlPlane,
            List<ProjectScheduledTaskHandler> handlers,
            Duration renewInterval) {
        this(projectId, instanceId, controlPlane, handlers, renewInterval, DEFAULT_MAX_CONSECUTIVE_RENEW_FAILURES);
    }

    public ProjectSchedulerRuntime(
            String projectId,
            String instanceId,
            SchedulerControlPlane controlPlane,
            List<ProjectScheduledTaskHandler> handlers,
            Duration renewInterval,
            int maxConsecutiveRenewFailures) {
        this.projectId = requireText(projectId, "项目 ID");
        this.instanceId = requireText(instanceId, "实例 ID");
        this.controlPlane = controlPlane;
        if (renewInterval == null || renewInterval.isZero() || renewInterval.isNegative()) {
            throw new IllegalArgumentException("租约续期间隔必须大于 0");
        }
        this.renewInterval = renewInterval;
        if (maxConsecutiveRenewFailures < 1) {
            throw new IllegalArgumentException("租约连续失败阈值必须大于 0");
        }
        this.maxConsecutiveRenewFailures = maxConsecutiveRenewFailures;
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                1,
                Thread.ofPlatform()
                        .daemon(true)
                        .name("kaiwu-scheduler-lease-", 0)
                        .factory(),
                new ThreadPoolExecutor.AbortPolicy());
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.leaseExecutor = executor;
        this.handlers = new LinkedHashMap<>();
        for (ProjectScheduledTaskHandler handler : handlers) {
            String taskType = requireText(handler.taskType(), "任务类型");
            if (this.handlers.putIfAbsent(taskType, handler) != null) {
                throw new IllegalArgumentException("定时任务类型重复注册：" + taskType);
            }
        }
    }

    public List<ProjectSchedulerJob> synchronize() {
        List<ProjectSchedulerHandlerRegistration> registrations = handlers.values().stream()
                .map(handler -> new ProjectSchedulerHandlerRegistration(handler.taskType(), handler.taskName()))
                .toList();
        List<ProjectSchedulerJob> jobs = controlPlane.synchronize(projectId, instanceId, registrations);
        for (ProjectSchedulerJob job : jobs) {
            if (!projectId.equals(job.projectId())) {
                throw new IllegalArgumentException("System 下发了其他项目的定时任务");
            }
            if (!handlers.containsKey(job.taskType())) {
                throw new IllegalStateException("System 下发了未注册的定时任务 Handler：" + job.taskType());
            }
        }
        return List.copyOf(jobs);
    }

    /**
     * @return 已取得中央租约并启动处理器时为 {@code true}；未抢到或本地仍有同次执行时为
     * {@code false}
     */
    public boolean execute(ProjectSchedulerJob job, Instant scheduledAt) {
        if (closed.get()) {
            throw new IllegalStateException("定时任务运行时已关闭");
        }
        if (!projectId.equals(job.projectId())) {
            throw new IllegalArgumentException("定时任务项目与当前项目不一致");
        }
        ProjectScheduledTaskHandler handler = handlers.get(job.taskType());
        if (handler == null) {
            throw new IllegalStateException("定时任务 Handler 未注册：" + job.taskType());
        }
        ExecutionKey executionKey = new ExecutionKey(job.id(), scheduledAt);
        if (!runningExecutions.add(executionKey)) {
            return false;
        }
        try {
            SchedulerClaim claim =
                    controlPlane.claim(projectId, instanceId, job.id(), job.configVersion(), scheduledAt);
            if (!claim.acquired()) {
                return false;
            }
            ProjectSchedulerLease lease = new ProjectSchedulerLease(
                    claim.executionId() + ":" + instanceId + ":" + UUID.randomUUID(), Thread.currentThread());
            activeLeases.add(lease);
            AtomicInteger consecutiveRenewFailures = new AtomicInteger();
            ScheduledFuture<?> heartbeat = leaseExecutor.scheduleWithFixedDelay(
                    () -> renewSafely(claim.executionId(), lease, consecutiveRenewFailures),
                    renewInterval.toMillis(),
                    renewInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
            try {
                Exception handlerFailure = null;
                try {
                    handler.execute(new ProjectScheduledTaskContext(
                            projectId, job.id(), claim.executionId(), scheduledAt, job.payload(), lease));
                } catch (Exception exception) {
                    handlerFailure = exception;
                }
                if (handlerFailure == null && !lease.isActive()) {
                    handlerFailure = new SchedulerLeaseLostException("处理器返回前执行租约已失效");
                }
                if (handlerFailure != null) {
                    controlPlane.complete(
                            projectId, instanceId, claim.executionId(), "FAILED", safeMessage(handlerFailure));
                    return true;
                }

                // SUCCESS 回传失败不是业务处理失败，不得落入 FAILED 分支。
                controlPlane.complete(projectId, instanceId, claim.executionId(), "SUCCESS", null);
                return true;
            } finally {
                heartbeat.cancel(false);
                lease.finish();
                activeLeases.remove(lease);
            }
        } finally {
            runningExecutions.remove(executionKey);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            activeLeases.forEach(lease -> lease.lose("定时任务运行时已关闭"));
            leaseExecutor.shutdownNow();
        }
    }

    private void renewSafely(String executionId, ProjectSchedulerLease lease, AtomicInteger consecutiveFailures) {
        if (!lease.isActive()) {
            return;
        }
        try {
            if (!controlPlane.renew(projectId, instanceId, executionId)) {
                LOGGER.warn("Kaiwu 定时任务执行租约续期被拒绝：executionId={}", executionId);
                lease.lose("定时任务执行租约续期被拒绝");
                return;
            }
            consecutiveFailures.set(0);
        } catch (Exception exception) {
            int failures = consecutiveFailures.incrementAndGet();
            LOGGER.warn("Kaiwu 定时任务执行租约续期失败：executionId={}, message={}", executionId, exception.getMessage());
            if (failures >= maxConsecutiveRenewFailures) {
                lease.lose("定时任务执行租约连续 " + failures + " 次续期失败");
            }
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.trim();
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private record ExecutionKey(String jobId, Instant scheduledAt) {}
}
