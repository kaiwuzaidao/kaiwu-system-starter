package com.kaiwu.starter.storage.model;

import java.time.LocalDateTime;

/**
 * 项目内统一文件记录，对应表 {@code kaiwu_file}（ADR 0041）。业务表只保存 {@code id}，
 * 不再各自保存 {@code storage_ref} 与 {@code object_key}。
 *
 * @param id 文件 ID（UUID）
 * @param storageRef 对象所在存储的引用，切换存储后据此找回旧文件
 * @param objectKey 本项目前缀下的对象 key
 * @param originalName 用户上传时的原始文件名，只用于展示
 * @param contentType MIME 类型
 * @param sizeBytes 字节数
 * @param sha256 内容摘要，十六进制小写；直传未复核内容时为空
 * @param bizType 业务类型，也是 object key 的一段
 * @param bizId 关联的业务记录 ID，尚未挂到业务记录时为空
 * @param status 生命周期状态
 * @param uploaderId 上传人用户 ID
 * @param createdAt 记录创建时间
 * @param confirmedAt 确认生效时间，待确认时为空
 * @param deletedAt 软删除时间，未删除时为空
 */
public record StoredFile(
        String id,
        String storageRef,
        String objectKey,
        String originalName,
        String contentType,
        long sizeBytes,
        String sha256,
        String bizType,
        String bizId,
        StoredFileStatus status,
        String uploaderId,
        LocalDateTime createdAt,
        LocalDateTime confirmedAt,
        LocalDateTime deletedAt) {}
