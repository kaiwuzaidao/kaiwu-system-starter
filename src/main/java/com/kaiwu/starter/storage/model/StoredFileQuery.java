package com.kaiwu.starter.storage.model;

/**
 * 文件列表的筛选条件；空字段表示不限。列表只返回 {@link StoredFileStatus#ACTIVE} 的记录。
 *
 * @param keyword 原始文件名关键字，按子串匹配
 * @param bizType 业务类型
 * @param bizId 关联的业务记录 ID
 * @param uploaderId 上传人用户 ID
 */
public record StoredFileQuery(String keyword, String bizType, String bizId, String uploaderId) {}
