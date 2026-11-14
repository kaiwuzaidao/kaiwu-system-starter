package com.kaiwu.starter.storage.controller;

import com.kaiwu.starter.RequirePermission;
import com.kaiwu.starter.storage.exception.ObjectNotFoundException;
import com.kaiwu.starter.storage.exception.ObjectStorageException;
import com.kaiwu.starter.storage.model.StoredFile;
import com.kaiwu.starter.storage.model.StoredFilePage;
import com.kaiwu.starter.storage.model.StoredFileQuery;
import com.kaiwu.starter.storage.service.StoredFileService;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目「文件」管理页使用的接口（ADR 0041）：列表、取下载地址、删除。上传不在此暴露，
 * 文件总是由业务功能产生并挂到业务记录上。权限码 {@code file:record:list|download|delete} 与生成项目的菜单一致。
 */
@RestController
@RequestMapping("/api/files")
public class StoredFileController {

    private static final Duration DEFAULT_DOWNLOAD_TTL = Duration.ofMinutes(5);

    private final StoredFileService service;
    private final Duration downloadTtl;

    /**
     * 创建控制器。
     *
     * @param service 文件服务
     * @param downloadTtl 预签名下载地址的有效期，为空时取 5 分钟
     */
    public StoredFileController(StoredFileService service, Duration downloadTtl) {
        this.service = service;
        this.downloadTtl = downloadTtl == null ? DEFAULT_DOWNLOAD_TTL : downloadTtl;
    }

    /**
     * 分页查询生效中的文件。
     *
     * @param keyword 原始文件名关键字
     * @param bizType 业务类型
     * @param bizId 关联的业务记录 ID
     * @param uploaderId 上传人用户 ID
     * @param current 页码，从 1 开始
     * @param size 每页条数，上限 100
     * @return 与生成项目一致的响应包络，数据为分页结果
     */
    @GetMapping
    @RequirePermission("file:record:list")
    public Envelope<StoredFilePage> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String bizId,
            @RequestParam(required = false) String uploaderId,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        return Envelope.ok(service.page(new StoredFileQuery(keyword, bizType, bizId, uploaderId), current, size));
    }

    /**
     * 取下载地址：存储支持预签名时返回直连地址；否则返回本接口的中转地址，前端带登录态拉取。
     *
     * @param id 文件 ID
     * @return 包含 {@code url}、{@code direct} 与原始文件名的响应
     */
    @GetMapping("/{id}/download")
    @RequirePermission("file:record:download")
    public Envelope<Map<String, Object>> download(@PathVariable String id) {
        StoredFile file = service.find(id).orElseThrow(() -> new ObjectNotFoundException(id));
        var direct = service.presignDownload(file, downloadTtl);
        String url = direct.map(URI::toString).orElse("/api/files/" + id + "/content");
        return Envelope.ok(Map.of("url", url, "direct", direct.isPresent(), "name", file.originalName()));
    }

    /**
     * 中转下载文件内容，仅在存储不支持预签名时使用。
     *
     * @param id 文件 ID
     * @return 以附件形式返回的内容流
     */
    @GetMapping("/{id}/content")
    @RequirePermission("file:record:download")
    public ResponseEntity<InputStreamResource> content(@PathVariable String id) {
        StoredFile file = service.find(id).orElseThrow(() -> new ObjectNotFoundException(id));
        InputStream stream = service.open(file);
        return ResponseEntity.ok()
                .contentType(mediaType(file.contentType()))
                .contentLength(file.sizeBytes())
                .header("X-Content-Type-Options", "nosniff")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.originalName(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(new InputStreamResource(stream));
    }

    /**
     * 软删除文件。业务记录仍引用它时，业务应先解除引用再删除。
     *
     * @param id 文件 ID
     * @return 成功响应
     */
    @DeleteMapping("/{id}")
    @RequirePermission("file:record:delete")
    public Envelope<Void> delete(@PathVariable String id) {
        service.delete(id);
        return Envelope.ok(null);
    }

    /**
     * 文件不存在统一返回 404。
     *
     * @param exception 不存在异常
     * @return 错误响应
     */
    @ExceptionHandler(ObjectNotFoundException.class)
    ResponseEntity<Envelope<Void>> notFound(ObjectNotFoundException exception) {
        return failure(HttpStatus.NOT_FOUND, "文件不存在");
    }

    /**
     * 存储不可用统一返回 502，不向浏览器暴露内部细节。
     *
     * @param exception 存储异常
     * @return 错误响应
     */
    @ExceptionHandler(ObjectStorageException.class)
    ResponseEntity<Envelope<Void>> storageFailure(ObjectStorageException exception) {
        return failure(HttpStatus.BAD_GATEWAY, "对象存储暂不可用");
    }

    /**
     * 参数错误统一返回 400。
     *
     * @param exception 参数异常
     * @return 错误响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Envelope<Void>> badRequest(IllegalArgumentException exception) {
        return failure(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /**
     * 构造失败响应。
     *
     * @param status HTTP 状态
     * @param message 面向用户的提示
     * @return 响应
     */
    private static ResponseEntity<Envelope<Void>> failure(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new Envelope<>(status.value(), message, null));
    }

    /**
     * 解析 MIME 类型，非法时按二进制流处理。
     *
     * @param value 记录里的类型
     * @return 可用的媒体类型
     */
    private static MediaType mediaType(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (IllegalArgumentException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /**
     * 与生成项目 {@code Result} 同形状的响应包络，前端请求层无需特殊处理。
     *
     * @param code 0 表示成功，否则为 HTTP 状态码
     * @param message 提示信息
     * @param data 数据
     * @param <T> 数据类型
     */
    public record Envelope<T>(int code, String message, T data) {

        /**
         * 构造成功响应。
         *
         * @param data 数据
         * @param <T> 数据类型
         * @return 成功包络
         */
        static <T> Envelope<T> ok(T data) {
            return new Envelope<>(0, "ok", data);
        }
    }
}
