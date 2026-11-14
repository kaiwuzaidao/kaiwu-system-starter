package com.kaiwu.starter.todo.client;

import java.time.Instant;

/** 单个收件人的不可变投递；版本、创建时间与正文由业务事务持久化，重试不得改写。 */
public record ManagedTodoOperation(
        String projectCode,
        String bizKey,
        String recipientUserId,
        long sequence,
        String state,
        boolean terminal,
        Instant operationCreatedAt,
        String title,
        String content,
        String linkUrl,
        Instant dueAt) {}
