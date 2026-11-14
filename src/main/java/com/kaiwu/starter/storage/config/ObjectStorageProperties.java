package com.kaiwu.starter.storage.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * 对象存储接入参数（{@code kaiwu.starter.storage.*}）。默认关闭，业务项目显式开启后才装配。
 *
 * @param enabled 是否装配 {@link com.kaiwu.starter.storage.service.ObjectStorage}
 * @param provider 存储实现：{@code local} 本机目录，{@code kaiwu} 向 System 申请临时凭证
 * @param maxObjectSize 单个对象的大小上限，默认 20MB；provider=kaiwu 时与平台配置取较小值
 * @param local 本地磁盘实现的参数
 * @param kaiwu 向 System 申请临时凭证的参数
 */
@ConfigurationProperties(prefix = "kaiwu.starter.storage")
public record ObjectStorageProperties(
        boolean enabled,
        @DefaultValue("local") Provider provider,
        @DefaultValue("20MB") DataSize maxObjectSize,
        @DefaultValue Local local,
        @DefaultValue Kaiwu kaiwu) {

    /** 可选的存储实现。 */
    public enum Provider {
        /** 本机目录，用于开发、测试与体验版。 */
        LOCAL,
        /** 向 System 申请 STS 临时凭证，经 S3 协议访问阿里云 OSS、MinIO 等（ADR 0039）。 */
        KAIWU
    }

    /**
     * 本地磁盘实现的参数。
     *
     * @param rootDirectory 存储根目录；启用本地实现时必填，多副本部署须指向共享目录
     */
    public record Local(Path rootDirectory) {}

    /**
     * 向 System 申请临时凭证的参数。
     *
     * @param systemBaseUrl System 地址，业务服务直连、不经网关，支持 {@code lb://}
     * @param credential 项目服务凭据，与 {@code kaiwu.starter.config.credential} 是同一个值
     * @param storageName 默认写入的存储名
     * @param checkInterval 后台检查续签的间隔
     * @param timeout 单次申请的超时
     */
    public record Kaiwu(
            String systemBaseUrl,
            String credential,
            @DefaultValue("default") String storageName,
            @DefaultValue("60s") Duration checkInterval,
            @DefaultValue("5s") Duration timeout) {

        /**
         * 不输出项目服务凭据。
         *
         * @return 不含凭据的描述
         */
        @Override
        public String toString() {
            return "Kaiwu[systemBaseUrl=" + systemBaseUrl + ", storageName=" + storageName + "]";
        }
    }
}
