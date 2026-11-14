package com.kaiwu.starter.storage.model;

/**
 * 直传的第一步结果：待确认的文件记录加上传地址。前端上传完成后必须调用确认接口，文件才会生效。
 *
 * @param file 状态为 {@link StoredFileStatus#PENDING} 的文件记录
 * @param upload 预签名上传地址与必须携带的请求头
 */
public record PendingFileUpload(StoredFile file, PresignedUpload upload) {}
