package com.kaiwu.starter.scheduler;

public record ProjectSchedulerJob(
        String id,
        String projectId,
        String taskType,
        String cronExpression,
        String zoneId,
        String payload,
        long configVersion) {}
