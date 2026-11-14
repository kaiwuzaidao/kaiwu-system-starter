package com.kaiwu.starter.storage.model;

/**
 * 已写入存储的对象元数据。业务记录保存 {@code storageRef} 与 {@code objectKey} 即可重新定位对象。
 *
 * @param storageRef 对象所在存储的引用标识
 * @param objectKey 本项目前缀下的对象 key
 * @param size 对象字节数
 * @param contentType 对象的 MIME 类型
 */
public record StoredObject(String storageRef, String objectKey, long size, String contentType) {}
