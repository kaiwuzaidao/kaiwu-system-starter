package com.kaiwu.starter.storage.model;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/**
 * 前端直传所需的预签名上传信息。前端必须原样携带 {@code headers}，否则签名校验失败。
 *
 * @param uri 上传地址
 * @param method HTTP 方法，目前固定为 {@code PUT}
 * @param headers 上传时必须携带的请求头
 * @param expiresAt 地址失效时间
 */
public record PresignedUpload(URI uri, String method, Map<String, String> headers, Instant expiresAt) {

    /**
     * 复制请求头，避免调用方修改返回值后影响签名语义。
     *
     * @param uri 上传地址
     * @param method HTTP 方法
     * @param headers 上传时必须携带的请求头
     * @param expiresAt 地址失效时间
     */
    public PresignedUpload {
        headers = Map.copyOf(headers);
    }
}
