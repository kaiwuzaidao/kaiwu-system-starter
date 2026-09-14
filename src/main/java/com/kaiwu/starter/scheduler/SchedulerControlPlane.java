package com.kaiwu.starter.scheduler;

import java.time.Instant;
import java.util.List;

public interface SchedulerControlPlane {

    List<ProjectSchedulerJob> synchronize(
            String projectId, String instanceId, List<ProjectSchedulerHandlerRegistration> handlers);

    SchedulerClaim claim(String projectId, String instanceId, String jobId, long configVersion, Instant scheduledAt);

    boolean renew(String projectId, String instanceId, String executionId);

    void complete(String projectId, String instanceId, String executionId, String status, String message);
}
