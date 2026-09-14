package com.kaiwu.starter.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

class ProjectSchedulerCoordinatorTest {

    @Test
    void computesTheSameScheduledInstantAndReplacesChangedConfiguration() {
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> firstFuture = mock(ScheduledFuture.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> secondFuture = mock(ScheduledFuture.class);
        Instant firstFire = Instant.parse("2026-07-29T00:05:00Z");
        Instant secondFire = Instant.parse("2026-07-29T00:10:00Z");
        doReturn(firstFuture).when(taskScheduler).schedule(any(Runnable.class), eq(firstFire));
        doReturn(secondFuture).when(taskScheduler).schedule(any(Runnable.class), eq(secondFire));
        ProjectSchedulerRuntime runtime =
                new ProjectSchedulerRuntime("project-1", "instance-a", mock(SchedulerControlPlane.class), List.of());
        ProjectSchedulerCoordinator coordinator = new ProjectSchedulerCoordinator(
                runtime, taskScheduler, Clock.fixed(Instant.parse("2026-07-29T00:00:01Z"), ZoneOffset.UTC));
        ProjectSchedulerJob first = job(1L, "0 */5 * * * *");

        coordinator.reconcile(List.of(first));

        verify(taskScheduler).schedule(any(Runnable.class), eq(firstFire));
        assertThat(coordinator.scheduledJobCount()).isEqualTo(1);

        ProjectSchedulerJob changed = job(2L, "0 */10 * * * *");
        coordinator.reconcile(List.of(changed));

        verify(firstFuture).cancel(false);
        verify(taskScheduler).schedule(any(Runnable.class), eq(secondFire));
        assertThat(coordinator.scheduledJobCount()).isEqualTo(1);
    }

    @Test
    void schedulesTheNextFireEvenWhenOneExecutionFailsUnexpectedly() {
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        org.mockito.ArgumentCaptor<Runnable> runnable = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        Instant fire = Instant.parse("2026-07-29T00:05:00Z");
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        doReturn(future).when(taskScheduler).schedule(runnable.capture(), eq(fire));
        ProjectSchedulerRuntime runtime = mock(ProjectSchedulerRuntime.class);
        ProjectSchedulerJob job = job(1L, "0 */5 * * * *");
        doThrow(new SchedulerRemoteException("control plane unavailable"))
                .when(runtime)
                .execute(job, fire);
        ProjectSchedulerCoordinator coordinator = new ProjectSchedulerCoordinator(
                runtime, taskScheduler, Clock.fixed(Instant.parse("2026-07-29T00:00:01Z"), ZoneOffset.UTC));

        coordinator.reconcile(List.of(job));
        runnable.getValue().run();

        verify(taskScheduler).schedule(any(Runnable.class), eq(fire));
        verify(taskScheduler).schedule(any(Runnable.class), eq(Instant.parse("2026-07-29T00:10:00Z")));
        verify(taskScheduler).schedule(any(Runnable.class), eq(Instant.parse("2026-07-29T00:11:00Z")));
        assertThat(coordinator.scheduledJobCount()).isEqualTo(1);
    }

    @Test
    void retriesRejectedClaimForTheSameFireTimeWithoutDelayingNextCron() {
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        Map<Instant, Runnable> tasks = new ConcurrentHashMap<>();
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        org.mockito.Mockito.doAnswer(invocation -> {
                    tasks.put(invocation.getArgument(1), invocation.getArgument(0));
                    return future;
                })
                .when(taskScheduler)
                .schedule(any(Runnable.class), any(Instant.class));
        ProjectSchedulerRuntime runtime = mock(ProjectSchedulerRuntime.class);
        ProjectSchedulerJob job = job(1L, "0 */5 * * * *");
        Instant scheduledAt = Instant.parse("2026-07-29T00:05:00Z");
        when(runtime.execute(job, scheduledAt)).thenReturn(false);
        ProjectSchedulerCoordinator coordinator = new ProjectSchedulerCoordinator(
                runtime,
                taskScheduler,
                Clock.fixed(Instant.parse("2026-07-29T00:00:01Z"), ZoneOffset.UTC),
                java.time.Duration.ofMinutes(1),
                2);

        coordinator.reconcile(List.of(job));
        tasks.get(scheduledAt).run();

        assertThat(tasks).containsKeys(Instant.parse("2026-07-29T00:06:00Z"), Instant.parse("2026-07-29T00:10:00Z"));

        tasks.get(Instant.parse("2026-07-29T00:06:00Z")).run();
        assertThat(tasks).containsKey(Instant.parse("2026-07-29T00:07:00Z"));

        tasks.get(Instant.parse("2026-07-29T00:07:00Z")).run();
        verify(runtime, times(3)).execute(job, scheduledAt);
        assertThat(tasks).doesNotContainKey(Instant.parse("2026-07-29T00:08:00Z"));
    }

    private static ProjectSchedulerJob job(long version, String cron) {
        return new ProjectSchedulerJob("job-1", "project-1", "report.daily", cron, "UTC", "{}", version);
    }
}
