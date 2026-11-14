package com.kaiwu.starter.storage.model;

import java.time.Instant;
import java.util.List;

/**
 * System 为本项目某个存储绑定签发的临时凭证（ADR 0039）。只保存在内存里，不落盘、不进日志。
 *
 * @param storageRef 绑定标识，业务记录保存它以便切换存储后仍能读取旧文件
 * @param storageName 项目内存储名
 * @param mode 访问模式：{@code READ_WRITE} 或 {@code READ_ONLY}
 * @param providerType 存储类型
 * @param endpoint 数据面地址
 * @param region 签名区域
 * @param bucket 桶名
 * @param pathStyle 是否 path-style
 * @param prefix 对象 key 前缀，固定为 {@code {projectCode}/}
 * @param maxObjectBytes 平台配置的单个对象字节上限
 * @param allowedContentTypes 平台配置的 MIME 类型白名单，空表示不限制
 * @param accessKeyId 临时 AccessKey ID
 * @param accessKeySecret 临时 AccessKey Secret
 * @param securityToken 会话令牌
 * @param expiresAt 失效时间
 */
public record StorageCredential(
        String storageRef,
        String storageName,
        String mode,
        String providerType,
        String endpoint,
        String region,
        String bucket,
        boolean pathStyle,
        String prefix,
        long maxObjectBytes,
        List<String> allowedContentTypes,
        String accessKeyId,
        String accessKeySecret,
        String securityToken,
        Instant expiresAt) {

    /**
     * 规范化白名单，避免空值在后续判断里到处兜底。
     *
     * @param storageRef 绑定标识
     * @param storageName 项目内存储名
     * @param mode 访问模式
     * @param providerType 存储类型
     * @param endpoint 数据面地址
     * @param region 签名区域
     * @param bucket 桶名
     * @param pathStyle 是否 path-style
     * @param prefix 对象 key 前缀
     * @param maxObjectBytes 单个对象字节上限
     * @param allowedContentTypes MIME 类型白名单
     * @param accessKeyId 临时 AccessKey ID
     * @param accessKeySecret 临时 AccessKey Secret
     * @param securityToken 会话令牌
     * @param expiresAt 失效时间
     */
    public StorageCredential {
        allowedContentTypes = allowedContentTypes == null ? List.of() : List.copyOf(allowedContentTypes);
    }

    /**
     * 判断是否只读绑定。
     *
     * @return 只读时返回 {@code true}
     */
    public boolean readOnly() {
        return !"READ_WRITE".equals(mode);
    }

    /**
     * 不输出临时密钥，避免被日志或异常带出。
     *
     * @return 只含标识、模式与失效时间的描述
     */
    @Override
    public String toString() {
        return "StorageCredential[storageRef=" + storageRef + ", storageName=" + storageName + ", mode=" + mode
                + ", expiresAt=" + expiresAt + "]";
    }
}
