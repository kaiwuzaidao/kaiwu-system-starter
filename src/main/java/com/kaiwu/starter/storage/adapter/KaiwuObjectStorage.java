package com.kaiwu.starter.storage.adapter;

import com.kaiwu.starter.storage.client.StorageCredentialClient;
import com.kaiwu.starter.storage.exception.ObjectStorageException;
import com.kaiwu.starter.storage.model.PresignedUpload;
import com.kaiwu.starter.storage.model.StorageCredential;
import com.kaiwu.starter.storage.model.StoredObject;
import com.kaiwu.starter.storage.service.ObjectKeyPolicy;
import com.kaiwu.starter.storage.service.ObjectStorage;
import com.kaiwu.starter.storage.service.ObjectStorageResolver;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * provider=kaiwu 的对象存储入口（ADR 0039）：写入走指定存储名当前生效的绑定，
 * 读取旧文件时按 {@code storage_ref} 找回原绑定。
 *
 * <p>作为 {@link ObjectStorage} 使用时，每次调用都重新取当前生效绑定，平台切换存储后新写入立即
 * 落到新账号；已经写入的文件应通过 {@link #forRef(String)} 访问。
 */
public final class KaiwuObjectStorage implements ObjectStorage, ObjectStorageResolver, AutoCloseable {

    private final StorageCredentialClient credentials;
    private final ObjectKeyPolicy keyPolicy;
    private final long starterMaxBytes;
    private final String storageName;
    private final Clock clock;
    private final Map<String, S3ObjectStorage> storages = new ConcurrentHashMap<>();

    /**
     * 创建入口。
     *
     * @param credentials 凭证客户端
     * @param keyPolicy 本项目的 key 策略
     * @param starterMaxBytes starter 配置的大小上限
     * @param storageName 默认写入的存储名，通常为 {@code default}
     * @param clock 时间来源
     */
    public KaiwuObjectStorage(
            StorageCredentialClient credentials,
            ObjectKeyPolicy keyPolicy,
            long starterMaxBytes,
            String storageName,
            Clock clock) {
        this.credentials = credentials;
        this.keyPolicy = keyPolicy;
        this.starterMaxBytes = starterMaxBytes;
        this.storageName = storageName;
        this.clock = clock;
    }

    /**
     * 按存储引用找回绑定；凭证地址参数变化时重建客户端。
     *
     * @param storageRef 业务记录里的存储引用
     * @return 对应的存储
     */
    @Override
    public ObjectStorage forRef(String storageRef) {
        StorageCredential current = credentials
                .byRef(storageRef)
                .orElseThrow(
                        () -> new ObjectStorageException("存储引用不可用：storageRef=" + storageRef + "，绑定可能已停用或 System 不可达"));
        return storageFor(current);
    }

    /**
     * 返回当前生效绑定的存储引用。
     *
     * @return 存储引用
     */
    @Override
    public String storageRef() {
        return active().storageRef();
    }

    /**
     * 写入当前生效绑定。
     *
     * @param objectKey 本项目前缀下的对象 key
     * @param content 对象内容
     * @param contentLength 声明的字节数
     * @param contentType MIME 类型
     * @return 写入后的对象元数据，其中的 storageRef 应保存到业务记录
     */
    @Override
    public StoredObject put(String objectKey, InputStream content, long contentLength, String contentType) {
        return active().put(objectKey, content, contentLength, contentType);
    }

    /**
     * 从当前生效绑定读取；旧文件请先 {@link #forRef(String)}。
     *
     * @param objectKey 对象 key
     * @return 对象内容流
     */
    @Override
    public InputStream get(String objectKey) {
        return active().get(objectKey);
    }

    /**
     * 查询当前生效绑定中的对象元数据。
     *
     * @param objectKey 对象 key
     * @return 元数据
     */
    @Override
    public Optional<StoredObject> stat(String objectKey) {
        return active().stat(objectKey);
    }

    /**
     * 从当前生效绑定删除对象。
     *
     * @param objectKey 对象 key
     */
    @Override
    public void delete(String objectKey) {
        active().delete(objectKey);
    }

    /**
     * S3 协议支持预签名地址。
     *
     * @return 恒为 {@code true}
     */
    @Override
    public boolean supportsPresignedUrls() {
        return true;
    }

    /**
     * 签发当前生效绑定中的下载地址。
     *
     * @param objectKey 对象 key
     * @param ttl 期望有效期
     * @return 下载地址
     */
    @Override
    public URI presignGet(String objectKey, Duration ttl) {
        return active().presignGet(objectKey, ttl);
    }

    /**
     * 签发当前生效绑定的直传地址。
     *
     * @param objectKey 对象 key
     * @param contentType MIME 类型
     * @param ttl 期望有效期
     * @return 上传信息
     */
    @Override
    public PresignedUpload presignPut(String objectKey, String contentType, Duration ttl) {
        return active().presignPut(objectKey, contentType, ttl);
    }

    /** 关闭全部 SDK 客户端。 */
    @Override
    public void close() {
        storages.values().forEach(S3ObjectStorage::close);
        storages.clear();
    }

    /**
     * 取当前生效绑定的存储。
     *
     * @return 存储
     */
    private S3ObjectStorage active() {
        StorageCredential current = credentials
                .active(storageName)
                .orElseThrow(() -> new ObjectStorageException("没有可用的生效存储绑定：" + storageName + "，请检查平台绑定或 System 是否可达"));
        return storageFor(current);
    }

    /**
     * 取或创建绑定对应的 S3 客户端；地址参数变化时替换旧客户端。
     *
     * @param current 当前凭证
     * @return 存储
     */
    private S3ObjectStorage storageFor(StorageCredential current) {
        return storages.compute(current.storageRef(), (ref, existing) -> {
            if (existing != null && existing.matches(current)) {
                return existing;
            }
            if (existing != null) {
                existing.close();
            }
            return new S3ObjectStorage(current, credentials, keyPolicy, starterMaxBytes, clock);
        });
    }
}
