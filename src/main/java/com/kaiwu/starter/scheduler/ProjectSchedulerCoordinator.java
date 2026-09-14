package com.kaiwu.starter.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;

/**
 * 把 System 下发的 Cron 配置协调为项目进程内的单次触发。
 *
 * <p>每次都使用 Cron 推导出的准确 scheduledAt，所有实例会为同一个逻辑触发时间
 * 向 System 申请同一把租约。</p>
 */
public final class ProjectSchedulerCoordinator implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectSchedulerCoordinator.class);
    private final ProjectSchedulerRuntime runtime;
    private final TaskScheduler taskScheduler;
    private final Clock clock;
    private final Duration claimRetryDelay;
    private final int maxClaimRetryAttempts;
    private final Map<String, ScheduledJob> scheduled = new HashMap<>();
    private final Map<RetryKey, ScheduledFuture<?>> retries = new HashMap<>();
    private boolean active;

    public ProjectSchedulerCoordinator(ProjectSchedulerRuntime runtime, TaskScheduler taskScheduler, Clock clock) {
        this(runtime, taskScheduler, clock, Duration.ofMinutes(6), 2);
    }

    public ProjectSchedulerCoordinator(
            ProjectSchedulerRuntime runtime,
            TaskScheduler taskScheduler,
            Clock clock,
            Duration claimRetryDelay,
            int maxClaimRetryAttempts) {
        this.runtime = runtime;
        this.taskScheduler = taskScheduler;
        this.clock = clock;
        if (claimRetryDelay == null || claimRetryDelay.isZero() || claimRetryDelay.isNegative()) {
            throw new IllegalArgumentException("抢占恢复重试间隔必须大于 0");
        }
        if (maxClaimRetryAttempts < 0) {
            throw new IllegalArgumentException("抢占恢复重试次数不能小于 0");
        }
        this.claimRetryDelay = claimRetryDelay;
        this.maxClaimRetryAttempts = maxClaimRetryAttempts;
    }

    public synchronized void reconcile(List<ProjectSchedulerJob> jobs) {
        active = true;
        Map<String, ProjectSchedulerJob> desired = new HashMap<>();
        for (ProjectSchedulerJob job : jobs) {
            desired.put(job.id(), job);
        }
        scheduled.entrySet().removeIf(entry -> {
            ProjectSchedulerJob job = desired.get(entry.getKey());
            if (job != null && entry.getValue().version() == job.configVersion()) {
                return false;
            }
            entry.getValue().future().cancel(false);
            return true;
        });
        retries.entrySet().removeIf(entry -> {
            ProjectSchedulerJob job = desired.get(entry.getKey().jobId());
            if (job != null && entry.getKey().version() == job.configVersion()) {
                return false;
            }
            entry.getValue().cancel(false);
            return true;
        });
        for (ProjectSchedulerJob job : jobs) {
            if (!scheduled.containsKey(job.id())) {
                scheduleNext(job, clock.instant());
            }
        }
    }

    int scheduledJobCount() {
        return scheduled.size();
    }

    @Override
    public synchronized void close() {
        active = false;
        scheduled.values().forEach(value -> value.future().cancel(false));
        scheduled.clear();
        retries.values().forEach(future -> future.cancel(false));
        retries.clear();
    }

    private synchronized void scheduleNext(ProjectSchedulerJob job, Instant after) {
        if (!active) {
            return;
        }
        ZoneId zoneId = ZoneId.of(job.zoneId());
        CronExpression cron = CronExpression.parse(job.cronExpression());
        ZonedDateTime next = cron.next(ZonedDateTime.ofInstant(after, zoneId));
        if (next == null) {
            return;
        }
        Instant scheduledAt = next.toInstant();
        ScheduledFuture<?> future = taskScheduler.schedule(() -> fireCron(job, scheduledAt), scheduledAt);
        if (future != null) {
            scheduled.put(job.id(), new ScheduledJob(job.configVersion(), scheduledAt, future));
        }
    }

    private void fireCron(ProjectSchedulerJob job, Instant scheduledAt) {
        synchronized (this) {
            ScheduledJob current = scheduled.get(job.id());
            if (!active
                    || current == null
                    || current.version() != job.configVersion()
                    || !current.scheduledAt().equals(scheduledAt)) {
                return;
            }
            scheduled.remove(job.id());
            // 先登记下一 cron，再执行可能长时间运行的业务 Handler。
            scheduleNext(job, latest(scheduledAt, clock.instant()));
        }
        executeAttempt(job, scheduledAt, scheduledAt, 0);
    }

    private void executeAttempt(ProjectSchedulerJob job, Instant scheduledAt, Instant attemptAt, int retryAttempt) {
        boolean acquired = false;
        try {
            acquired = runtime.execute(job, scheduledAt);
        } catch (Exception exception) {
            LOGGER.error("Kaiwu 定时任务触发失败，将按恢复策略重试：jobId={}, scheduledAt={}", job.id(), scheduledAt, exception);
        }
        if (!acquired) {
            scheduleRetry(job, scheduledAt, attemptAt, retryAttempt);
        }
    }

    private synchronized void scheduleRetry(
            ProjectSchedulerJob job, Instant scheduledAt, Instant previousAttemptAt, int completedRetryAttempts) {
        if (!active || completedRetryAttempts >= maxClaimRetryAttempts) {
            return;
        }
        RetryKey key = new RetryKey(job.id(), scheduledAt, job.configVersion());
        ScheduledFuture<?> existing = retries.remove(key);
        if (existing != null) {
            existing.cancel(false);
        }
        Instant retryAt = latest(clock.instant(), previousAttemptAt).plus(claimRetryDelay);
        int nextRetryAttempt = completedRetryAttempts + 1;
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> {
                    synchronized (ProjectSchedulerCoordinator.this) {
                        if (!active || retries.remove(key) == null) {
                            return;
                        }
                    }
                    executeAttempt(job, scheduledAt, retryAt, nextRetryAttempt);
                },
                retryAt);
        if (future != null) {
            retries.put(key, future);
        }
    }

    private static Instant latest(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private record ScheduledJob(long version, Instant scheduledAt, ScheduledFuture<?> future) {}

    private record RetryKey(String jobId, Instant scheduledAt, long version) {}
}
