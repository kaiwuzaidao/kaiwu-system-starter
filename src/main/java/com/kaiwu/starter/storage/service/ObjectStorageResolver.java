package com.kaiwu.starter.storage.service;

/**
 * 按业务记录里保存的 {@code storage_ref} 找回对应存储（ADR 0039 第 5 节）。
 *
 * <p>写入总是走 {@link ObjectStorage} 默认 Bean（当前生效绑定）；读取、删除和签发下载地址时，
 * 业务应先用记录里的 {@code storage_ref} 调用 {@link #forRef(String)}，这样平台切换存储后
 * 旧文件仍然可读。
 */
public interface ObjectStorageResolver {

    /**
     * 取保存该对象时所用的存储。
     *
     * @param storageRef 业务记录里的存储引用
     * @return 对应的存储
     * @throws com.kaiwu.starter.storage.exception.ObjectStorageException 引用未知、绑定已停用或凭证不可用时抛出
     */
    ObjectStorage forRef(String storageRef);
}
