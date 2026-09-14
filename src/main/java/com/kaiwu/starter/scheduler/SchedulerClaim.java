package com.kaiwu.starter.scheduler;

public record SchedulerClaim(boolean acquired, String executionId) {

    public static SchedulerClaim acquired(String executionId) {
        return new SchedulerClaim(true, executionId);
    }

    public static SchedulerClaim rejected() {
        return new SchedulerClaim(false, null);
    }
}
