package com.kaiwu.starter.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProjectSchedulerRuntimeTest {

    @Test
    void multipleProjectInstancesExecuteTheSameFireTimeOnlyOnce() {
        FakeControlPlane controlPlane = new FakeControlPlane();
        AtomicInteger executions = new AtomicInteger();
        ProjectScheduledTaskHandler handler = handler("order.timeout-close", executions);
        ProjectSchedulerRuntime first =
                new ProjectSchedulerRuntime("project-1", "instance-a", controlPlane, List.of(handler));
        ProjectSchedulerRuntime second =
                new ProjectSchedulerRuntime("project-1", "instance-b", controlPlane, List.of(handler));
        ProjectSchedulerJob job = new ProjectSchedulerJob(
                "job-1", "project-1", "order.timeout-close", "0 */5 * * * *", "Asia/Shanghai", "{\"minutes\":30}", 1L);
        Instant scheduledAt = Instant.parse("2026-07-29T01:00:00Z");

        first.execute(job, scheduledAt);
        second.execute(job, scheduledAt);

        assertThat(executions).hasValue(1);
        assertThat(controlPlane.completed).containsExactly("execution-1:SUCCESS");
    }

    @Test
    void refusesConfigurationForAnotherProjectBeforeClaiming() {
        FakeControlPlane controlPlane = new FakeControlPlane();
        ProjectSchedulerRuntime runtime = new ProjectSchedulerRuntime(
                "project-1", "instance-a", controlPlane, List.of(handler("report.daily", new AtomicInteger())));
        ProjectSchedulerJob foreignJob =
                new ProjectSchedulerJob("job-2", "project-2", "report.daily", "0 0 1 * * *", "UTC", "{}", 1L);

        assertThatThrownBy(() -> runtime.execute(foreignJob, Instant.parse("2026-07-29T01:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("项目");
        assertThat(controlPlane.claimed).isEmpty();
    }

    @Test
    void refusesUnregisteredHandlerFromRemoteConfiguration() {
        FakeControlPlane controlPlane = new FakeControlPlane();
        ProjectSchedulerRuntime runtime =
                new ProjectSchedulerRuntime("project-1", "instance-a", controlPlane, List.of());
        ProjectSchedulerJob job =
                new ProjectSchedulerJob("job-3", "project-1", "arbitrary.class.Name", "0 0 1 * * *", "UTC", "{}", 1L);

        assertThatThrownBy(() -> runtime.execute(job, Instant.parse("2026-07-29T01:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未注册");
        assertThat(controlPlane.claimed).isEmpty();
    }

    @Test
    void rejectsForeignOrUnknownJobsDuringSynchronization() {
        FakeControlPlane controlPlane = new FakeControlPlane();
        controlPlane.synchronizedJobs = List.of(
                new ProjectSchedulerJob("job-foreign", "project-2", "report.daily", "0 0 1 * * *", "UTC", "{}", 1L));
        ProjectSchedulerRuntime runtime = new ProjectSchedulerRuntime(
                "project-1", "instance-a", controlPlane, List.of(handler("report.daily", new AtomicInteger())));

        assertThatThrownBy(runtime::synchronize)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("项目");
        runtime.close();
    }

    @Test
    void renewsLeaseWhileAHandlerIsStillRunning() throws Exception {
        FakeControlPlane controlPlane = new FakeControlPlane();
        CountDownLatch renewed = new CountDownLatch(1);
        controlPlane.renewed = renewed;
        ProjectScheduledTaskHandler handler = new ProjectScheduledTaskHandler() {
            @Override
            public String taskType() {
                return "report.slow";
            }

            @Override
            public void execute(ProjectScheduledTaskContext context) throws Exception {
                assertThat(renewed.await(1, TimeUnit.SECONDS)).isTrue();
            }
        };
        ProjectSchedulerRuntime runtime = new ProjectSchedulerRuntime(
                "project-1", "instance-a", controlPlane, List.of(handler), Duration.ofMillis(10));
        ProjectSchedulerJob job =
                new ProjectSchedulerJob("job-slow", "project-1", "report.slow", "0 0 1 * * *", "UTC", "{}", 1L);

        runtime.execute(job, Instant.parse("2026-07-29T01:00:00Z"));
        runtime.close();

        assertThat(controlPlane.renewCount).hasPositiveValue();
        assertThat(controlPlane.completed).containsExactly("execution-1:SUCCESS");
    }

    @Test
    void successfulHandlerDoesNotReportFailedWhenSuccessCompletionFails() {
        FakeControlPlane controlPlane = new FakeControlPlane();
        controlPlane.failSuccessfulCompletion = true;
        ProjectSchedulerRuntime runtime = new ProjectSchedulerRuntime(
                "project-1", "instance-a", controlPlane, List.of(handler("report.daily", new AtomicInteger())));
        ProjectSchedulerJob job = job("report.daily");

        assertThatThrownBy(() -> runtime.execute(job, Instant.parse("2026-07-29T01:00:00Z")))
                .isInstanceOf(SchedulerRemoteException.class)
                .hasMessageContaining("complete unavailable");

        assertThat(controlPlane.completionAttempts).containsExactly("execution-1:SUCCESS");
        runtime.close();
    }

    @Test
    void rejectedRenewalSignalsAndInterruptsTheRunningHandler() {
        FakeControlPlane controlPlane = new FakeControlPlane();
        controlPlane.renewResult = false;
        AtomicBoolean observedLostLease = new AtomicBoolean();
        ProjectScheduledTaskHandler handler = new ProjectScheduledTaskHandler() {
            @Override
            public String taskType() {
                return "report.slow";
            }

            @Override
            public void execute(ProjectScheduledTaskContext context) {
                try {
                    new CountDownLatch(1).await(2, TimeUnit.SECONDS);
                } catch (InterruptedException expected) {
                    observedLostLease.set(!context.isLeaseActive());
                    assertThatThrownBy(context::assertLeaseActive).isInstanceOf(SchedulerLeaseLostException.class);
                }
            }
        };
        ProjectSchedulerRuntime runtime = new ProjectSchedulerRuntime(
                "project-1", "instance-a", controlPlane, List.of(handler), Duration.ofMillis(10), 3);

        runtime.execute(job("report.slow"), Instant.parse("2026-07-29T01:00:00Z"));

        assertThat(observedLostLease).isTrue();
        assertThat(controlPlane.completionAttempts).containsExactly("execution-1:FAILED");
        runtime.close();
    }

    @Test
    void repeatedRenewalFailuresSignalLeaseLossBeforeRecovery() {
        FakeControlPlane controlPlane = new FakeControlPlane();
        controlPlane.renewException = new SchedulerRemoteException("renew unavailable");
        AtomicBoolean observedLostLease = new AtomicBoolean();
        ProjectScheduledTaskHandler handler = new ProjectScheduledTaskHandler() {
            @Override
            public String taskType() {
                return "report.partitioned";
            }

            @Override
            public void execute(ProjectScheduledTaskContext context) {
                try {
                    new CountDownLatch(1).await(2, TimeUnit.SECONDS);
                } catch (InterruptedException expected) {
                    observedLostLease.set(!context.isLeaseActive());
                }
            }
        };
        ProjectSchedulerRuntime runtime = new ProjectSchedulerRuntime(
                "project-1", "instance-a", controlPlane, List.of(handler), Duration.ofMillis(10), 2);

        runtime.execute(job("report.partitioned"), Instant.parse("2026-07-29T01:00:00Z"));

        assertThat(controlPlane.renewCount).hasValue(2);
        assertThat(observedLostLease).isTrue();
        runtime.close();
    }

    @Test
    void sameRuntimeDoesNotReclaimWhileThePreviousHandlerIsStillActive() throws Exception {
        FakeControlPlane controlPlane = new FakeControlPlane();
        controlPlane.renewResult = false;
        CountDownLatch leaseLost = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        ProjectScheduledTaskHandler handler = new ProjectScheduledTaskHandler() {
            @Override
            public String taskType() {
                return "report.stubborn";
            }

            @Override
            public void execute(ProjectScheduledTaskContext context) throws Exception {
                while (releaseHandler.getCount() > 0) {
                    try {
                        releaseHandler.await();
                    } catch (InterruptedException ignored) {
                        leaseLost.countDown();
                    }
                }
            }
        };
        ProjectSchedulerRuntime runtime = new ProjectSchedulerRuntime(
                "project-1", "instance-a", controlPlane, List.of(handler), Duration.ofMillis(10), 3);
        ProjectSchedulerJob job = job("report.stubborn");
        Instant scheduledAt = Instant.parse("2026-07-29T01:00:00Z");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> runtime.execute(job, scheduledAt));
            assertThat(leaseLost.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(runtime.execute(job, scheduledAt)).isFalse();
            assertThat(controlPlane.claimCount).hasValue(1);
        } finally {
            releaseHandler.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
            runtime.close();
        }
    }

    private static ProjectSchedulerJob job(String taskType) {
        return new ProjectSchedulerJob("job-1", "project-1", taskType, "0 0 1 * * *", "UTC", "{}", 1L);
    }

    private static ProjectScheduledTaskHandler handler(String code, AtomicInteger executions) {
        return new ProjectScheduledTaskHandler() {
            @Override
            public String taskType() {
                return code;
            }

            @Override
            public void execute(ProjectScheduledTaskContext context) {
                executions.incrementAndGet();
            }
        };
    }

    private static final class FakeControlPlane implements SchedulerControlPlane {
        private final Set<String> claimed = ConcurrentHashMap.newKeySet();
        private final java.util.List<String> completed = new java.util.ArrayList<>();
        private final java.util.List<String> completionAttempts = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicInteger renewCount = new AtomicInteger();
        private final AtomicInteger claimCount = new AtomicInteger();
        private List<ProjectSchedulerJob> synchronizedJobs = List.of();
        private CountDownLatch renewed;
        private boolean renewResult = true;
        private RuntimeException renewException;
        private boolean failSuccessfulCompletion;

        @Override
        public List<ProjectSchedulerJob> synchronize(
                String projectId, String instanceId, List<ProjectSchedulerHandlerRegistration> handlers) {
            return synchronizedJobs;
        }

        @Override
        public SchedulerClaim claim(
                String projectId, String instanceId, String jobId, long configVersion, Instant scheduledAt) {
            claimCount.incrementAndGet();
            String key = jobId + ":" + scheduledAt;
            if (!claimed.add(key)) {
                return SchedulerClaim.rejected();
            }
            return SchedulerClaim.acquired("execution-1");
        }

        @Override
        public boolean renew(String projectId, String instanceId, String executionId) {
            renewCount.incrementAndGet();
            if (renewException != null) {
                throw renewException;
            }
            if (renewed != null) {
                renewed.countDown();
            }
            return renewResult;
        }

        @Override
        public void complete(String projectId, String instanceId, String executionId, String status, String message) {
            completionAttempts.add(executionId + ":" + status);
            if (failSuccessfulCompletion && "SUCCESS".equals(status)) {
                throw new SchedulerRemoteException("complete unavailable");
            }
            completed.add(executionId + ":" + status);
        }
    }
}
