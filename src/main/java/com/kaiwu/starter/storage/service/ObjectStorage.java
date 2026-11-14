package com.kaiwu.starter.storage.service;

import com.kaiwu.starter.storage.model.PresignedUpload;
import com.kaiwu.starter.storage.model.StoredObject;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * 业务项目使用的对象存储入口（ADR 0039）。
 *
 * <p>业务代码只面向本接口编程，不直接依赖任何厂商 SDK；切换本地磁盘、阿里云 OSS 或其他
 * S3 兼容存储只改配置。使用约定：
 * <ul>
 *   <li>object key 由 {@link ObjectKeyPolicy} 生成，必须位于本项目前缀之下；越界 key 一律拒绝，
 *       与生产环境临时凭证的前缀限制保持一致，避免开发期能写、上线后才失败。</li>
 *   <li>业务记录只保存 {@link StoredObject#storageRef()} 与 {@link StoredObject#objectKey()}，
 *       不保存完整 URL；需要展示时再调用 {@link #presignGet(String, Duration)}。</li>
 *   <li>本接口不做业务权限判断。调用方必须先确认当前用户有权访问该文件对应的业务记录。</li>
 * </ul>
 */
public interface ObjectStorage {

    /**
     * 返回当前存储的引用标识，业务记录据此在切换存储后仍能定位旧文件。
     *
     * @return 非空的存储引用，例如本地磁盘为 {@code local}
     */
    String storageRef();

    /**
     * 写入对象；同一 key 已存在时整体覆盖，覆盖前读到的旧内容不会与新内容混合。
     *
     * @param objectKey 本项目前缀下的对象 key
     * @param content 对象内容，由调用方负责关闭
     * @param contentLength 声明的字节数；未知时传 {@code -1}，已知时与实际长度不符会拒绝写入
     * @param contentType MIME 类型；为空时按 {@code application/octet-stream} 保存
     * @return 写入后的对象元数据
     * @throws com.kaiwu.starter.storage.exception.ObjectStorageException key 非法、超过大小上限或写入失败时抛出，
     *     失败时不会留下半个对象
     */
    StoredObject put(String objectKey, InputStream content, long contentLength, String contentType);

    /**
     * 打开对象内容。
     *
     * @param objectKey 本项目前缀下的对象 key
     * @return 对象内容流，调用方必须关闭
     * @throws com.kaiwu.starter.storage.exception.ObjectNotFoundException 对象不存在时抛出
     */
    InputStream get(String objectKey);

    /**
     * 查询对象元数据，用于直传完成后的确认环节复核大小与类型。
     *
     * @param objectKey 本项目前缀下的对象 key
     * @return 对象存在时返回元数据，不存在时返回空
     */
    Optional<StoredObject> stat(String objectKey);

    /**
     * 删除对象；对象不存在时视为已删除，可安全重试。
     *
     * @param objectKey 本项目前缀下的对象 key
     */
    void delete(String objectKey);

    /**
     * 判断当前存储能否签发预签名 URL。不支持时业务应回退为后端中转上传和下载。
     *
     * @return 支持预签名 URL 时返回 {@code true}
     */
    boolean supportsPresignedUrls();

    /**
     * 签发限时下载地址。
     *
     * @param objectKey 本项目前缀下的对象 key
     * @param ttl 期望有效期；实现可以缩短，但不会延长
     * @return 可直接交给浏览器的下载地址
     * @throws com.kaiwu.starter.storage.exception.ObjectStorageException 当前存储不支持预签名时抛出
     */
    URI presignGet(String objectKey, Duration ttl);

    /**
     * 签发限时直传地址，供前端绕过业务后端直接上传。上传完成后业务必须调用
     * {@link #stat(String)} 复核，不能只信任前端回报的大小与类型。
     *
     * @param objectKey 本项目前缀下的对象 key
     * @param contentType 上传时必须携带的 MIME 类型
     * @param ttl 期望有效期；实现可以缩短，但不会延长
     * @return 上传地址、方法与必须携带的请求头
     * @throws com.kaiwu.starter.storage.exception.ObjectStorageException 当前存储不支持预签名时抛出
     */
    PresignedUpload presignPut(String objectKey, String contentType, Duration ttl);
}
