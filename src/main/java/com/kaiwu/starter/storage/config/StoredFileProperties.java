package com.kaiwu.starter.storage.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 项目内统一文件记录的配置（ADR 0041）。只有 {@code kaiwu.starter.storage.files.enabled=true} 时装配。
 *
 * @param enabled 是否启用文件记录、管理接口与清理任务
 * @param uploadTtl 直传地址的有效期，默认 15 分钟
 * @param downloadTtl 下载地址的有效期，默认 5 分钟
 * @param pendingTtl 直传后超过该时间仍未确认的记录视为孤儿，默认 24 小时
 * @param deletedRetention 软删除后保留多久才真正删除对象，默认 7 天，用于误删恢复
 */
@ConfigurationProperties(prefix = "kaiwu.starter.storage.files")
public record StoredFileProperties(
        boolean enabled,
        @DefaultValue("15m") Duration uploadTtl,
        @DefaultValue("5m") Duration downloadTtl,
        @DefaultValue("24h") Duration pendingTtl,
        @DefaultValue("7d") Duration deletedRetention) {}
