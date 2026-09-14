package com.kaiwu.starter.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ProjectSchedulerLifecycleTest {

    @Test
    void synchronizationReturningAfterStopCannotReconcile() throws Exception {
        ProjectSchedulerRuntime runtime = mock(ProjectSchedulerRuntime.class);
        ProjectSchedulerCoordinator coordinator = mock(ProjectSchedulerCoordinator.class);
        CountDownLatch synchronizeStarted = new CountDownLatch(1);
        CountDownLatch releaseSynchronize = new CountDownLatch(1);
        CountDownLatch synchronizeReturned = new CountDownLatch(1);
        ProjectSchedulerJob job = mock(ProjectSchedulerJob.class);
        when(runtime.synchronize()).thenAnswer(invocation -> {
            synchronizeStarted.countDown();
            while (releaseSynchronize.getCount() > 0) {
                try {
                    releaseSynchronize.await();
                } catch (InterruptedException ignored) {
                    // 模拟不可取消的远程调用在 stop 后仍成功返回。
                }
            }
            synchronizeReturned.countDown();
            return List.of(job);
        });
        ProjectSchedulerLifecycle lifecycle =
                new ProjectSchedulerLifecycle(runtime, coordinator, Duration.ofSeconds(5));

        lifecycle.start();
        assertThat(synchronizeStarted.await(1, TimeUnit.SECONDS)).isTrue();
        lifecycle.stop();
        releaseSynchronize.countDown();

        assertThat(synchronizeReturned.await(1, TimeUnit.SECONDS)).isTrue();
        verify(coordinator, after(100).never()).reconcile(List.of(job));
    }

    @Test
    void lifecycleCanStartAgainAfterStop() {
        ProjectSchedulerRuntime runtime = mock(ProjectSchedulerRuntime.class);
        ProjectSchedulerCoordinator coordinator = mock(ProjectSchedulerCoordinator.class);
        when(runtime.synchronize()).thenReturn(List.of());
        ProjectSchedulerLifecycle lifecycle =
                new ProjectSchedulerLifecycle(runtime, coordinator, Duration.ofSeconds(5));

        lifecycle.start();
        verify(runtime, timeout(1000).times(1)).synchronize();
        lifecycle.stop();
        lifecycle.start();

        verify(runtime, timeout(1000).times(2)).synchronize();
        assertThat(lifecycle.isRunning()).isTrue();
        verify(runtime, never()).close();
        lifecycle.stop();
    }
}
