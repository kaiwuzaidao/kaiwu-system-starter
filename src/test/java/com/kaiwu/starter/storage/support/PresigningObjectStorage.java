package com.kaiwu.starter.storage.support;

import com.kaiwu.starter.storage.exception.ObjectNotFoundException;
import com.kaiwu.starter.storage.model.PresignedUpload;
import com.kaiwu.starter.storage.model.StoredObject;
import com.kaiwu.starter.storage.service.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** 测试用的内存存储，支持预签名，可直接模拟前端「直传」把对象放进去。 */
public class PresigningObjectStorage implements ObjectStorage {

    private final Map<String, byte[]> contents = new HashMap<>();
    private final Map<String, String> types = new HashMap<>();
    private final boolean presign;

    /**
     * 创建内存存储。
     *
     * @param presign 是否声明支持预签名 URL
     */
    public PresigningObjectStorage(boolean presign) {
        this.presign = presign;
    }

    /** 返回内存存储的固定引用。 */
    @Override
    public String storageRef() {
        return "mem";
    }

    /** 写入内存对象。 */
    @Override
    public StoredObject put(String objectKey, InputStream content, long contentLength, String contentType) {
        try {
            byte[] bytes = content.readAllBytes();
            contents.put(objectKey, bytes);
            types.put(objectKey, contentType);
            return new StoredObject("mem", objectKey, bytes.length, contentType);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 读取内存对象，不存在时抛出异常。 */
    @Override
    public InputStream get(String objectKey) {
        byte[] bytes = contents.get(objectKey);
        if (bytes == null) {
            throw new ObjectNotFoundException(objectKey);
        }
        return new ByteArrayInputStream(bytes);
    }

    /** 查询内存对象元数据。 */
    @Override
    public Optional<StoredObject> stat(String objectKey) {
        byte[] bytes = contents.get(objectKey);
        return bytes == null
                ? Optional.empty()
                : Optional.of(new StoredObject("mem", objectKey, bytes.length, types.get(objectKey)));
    }

    /** 删除内存对象。 */
    @Override
    public void delete(String objectKey) {
        contents.remove(objectKey);
        types.remove(objectKey);
    }

    /** 按构造参数声明是否支持预签名。 */
    @Override
    public boolean supportsPresignedUrls() {
        return presign;
    }

    /** 返回固定格式的下载地址。 */
    @Override
    public URI presignGet(String objectKey, Duration ttl) {
        return URI.create("https://storage.test/" + objectKey + "?ttl=" + ttl.toSeconds());
    }

    /** 返回固定格式的上传地址。 */
    @Override
    public PresignedUpload presignPut(String objectKey, String contentType, Duration ttl) {
        return new PresignedUpload(
                URI.create("https://storage.test/" + objectKey),
                "PUT",
                Map.of("Content-Type", contentType),
                Instant.now().plus(ttl));
    }

    /**
     * 模拟前端直传：把字节直接放进存储。
     *
     * @param objectKey 对象 key
     * @param bytes 内容
     * @param contentType MIME 类型
     */
    public void simulateDirectUpload(String objectKey, byte[] bytes, String contentType) {
        contents.put(objectKey, bytes);
        types.put(objectKey, contentType);
    }

    /**
     * 当前是否还保存着该对象。
     *
     * @param objectKey 对象 key
     * @return 存在时为 {@code true}
     */
    public boolean contains(String objectKey) {
        return contents.containsKey(objectKey);
    }
}
