package com.kaiwu.starter.storage.adapter;

import com.kaiwu.starter.storage.client.StorageCredentialClient;
import com.kaiwu.starter.storage.exception.ObjectNotFoundException;
import com.kaiwu.starter.storage.exception.ObjectStorageException;
import com.kaiwu.starter.storage.model.PresignedUpload;
import com.kaiwu.starter.storage.model.StorageCredential;
import com.kaiwu.starter.storage.model.StoredObject;
import com.kaiwu.starter.storage.service.ObjectContentTypes;
import com.kaiwu.starter.storage.service.ObjectKeyPolicy;
import com.kaiwu.starter.storage.service.ObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/**
 * 通过 S3 协议访问单个存储绑定的实现（ADR 0039），阿里云 OSS、MinIO 等共用。
 *
 * <p>每次请求都从 {@link StorageCredentialClient} 的内存快照取当前临时凭证，续签后无需重建客户端。
 * 关闭了 SDK 默认的分块上传编码与请求校验和：部分 S3 兼容实现（含阿里云 OSS）不接受它们。
 */
public final class S3ObjectStorage implements ObjectStorage, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);
    /** 预签名地址至少比凭证早这么久失效，避免浏览器拿到一个即将失效的地址。 */
    private static final Duration PRESIGN_MARGIN = Duration.ofSeconds(30);

    private static final int COPY_BUFFER_BYTES = 8192;

    private final String storageRef;
    private final StorageCredentialClient credentials;
    private final ObjectKeyPolicy keyPolicy;
    private final long starterMaxBytes;
    private final Clock clock;
    private final StorageCredential template;
    private final S3Client client;
    private final S3Presigner presigner;

    /**
     * 按首次取得的凭证确定地址、区域、桶与访问风格并创建客户端。
     *
     * @param initial 该绑定当前的凭证，只取其中的地址类参数
     * @param credentials 凭证快照来源，每次请求都从这里取最新凭证
     * @param keyPolicy 本项目的 key 策略
     * @param starterMaxBytes starter 配置的大小上限，与平台配置取较小值
     * @param clock 时间来源
     */
    public S3ObjectStorage(
            StorageCredential initial,
            StorageCredentialClient credentials,
            ObjectKeyPolicy keyPolicy,
            long starterMaxBytes,
            Clock clock) {
        this.storageRef = initial.storageRef();
        this.credentials = credentials;
        this.keyPolicy = keyPolicy;
        this.starterMaxBytes = starterMaxBytes;
        this.clock = clock;
        this.template = initial;
        URI endpoint = URI.create(initial.endpoint());
        Region region = Region.of(initial.region());
        AwsCredentialsProvider provider = this::currentSessionCredentials;
        S3Configuration configuration = S3Configuration.builder()
                .pathStyleAccessEnabled(initial.pathStyle())
                .chunkedEncodingEnabled(false)
                .build();
        this.client = S3Client.builder()
                .endpointOverride(endpoint)
                .region(region)
                .credentialsProvider(provider)
                .serviceConfiguration(configuration)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClient(UrlConnectionHttpClient.create())
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(region)
                .credentialsProvider(provider)
                .serviceConfiguration(configuration)
                .build();
    }

    /**
     * 判断当前凭证的地址类参数是否仍与创建时一致；不一致说明平台改了账号，需要重建。
     *
     * @param current 当前凭证
     * @return 一致时返回 {@code true}
     */
    boolean matches(StorageCredential current) {
        return template.endpoint().equals(current.endpoint())
                && template.region().equals(current.region())
                && template.bucket().equals(current.bucket())
                && template.pathStyle() == current.pathStyle();
    }

    /**
     * 返回本绑定的存储引用。
     *
     * @return 存储引用
     */
    @Override
    public String storageRef() {
        return storageRef;
    }

    /**
     * 写入对象：校验前缀、只读、类型白名单与大小上限；未知长度时先落临时文件再上传。
     *
     * @param objectKey 本项目前缀下的对象 key
     * @param content 对象内容，由调用方负责关闭
     * @param contentLength 声明的字节数；未知时传 {@code -1}
     * @param contentType MIME 类型
     * @return 写入后的对象元数据
     */
    @Override
    public StoredObject put(String objectKey, InputStream content, long contentLength, String contentType) {
        keyPolicy.requireOwned(objectKey);
        StorageCredential current = writable();
        String type = ObjectContentTypes.normalize(contentType);
        ObjectContentTypes.requireAllowed(type, current.allowedContentTypes());
        long limit = Math.min(starterMaxBytes, current.maxObjectBytes());
        if (contentLength > limit) {
            throw new ObjectStorageException("对象超过大小上限 " + limit + " 字节：" + objectKey);
        }
        if (contentLength >= 0) {
            upload(objectKey, type, contentLength, RequestBody.fromInputStream(content, contentLength));
            return new StoredObject(storageRef, objectKey, contentLength, type);
        }
        Path buffer = null;
        try {
            buffer = Files.createTempFile("kaiwu-upload-", ".tmp");
            long size = copyWithLimit(content, buffer, limit, objectKey);
            upload(objectKey, type, size, RequestBody.fromFile(buffer));
            return new StoredObject(storageRef, objectKey, size, type);
        } catch (IOException exception) {
            throw new ObjectStorageException("缓冲上传内容失败：" + objectKey, exception);
        } finally {
            deleteQuietly(buffer);
        }
    }

    /**
     * 打开对象内容流。
     *
     * @param objectKey 本项目前缀下的对象 key
     * @return 对象内容流，调用方必须关闭
     */
    @Override
    public InputStream get(String objectKey) {
        keyPolicy.requireOwned(objectKey);
        try {
            return client.getObject(request -> request.bucket(template.bucket()).key(objectKey));
        } catch (NoSuchKeyException exception) {
            throw new ObjectNotFoundException(objectKey);
        } catch (SdkException exception) {
            throw translate("读取对象失败：" + objectKey, exception);
        }
    }

    /**
     * 查询对象元数据。
     *
     * @param objectKey 本项目前缀下的对象 key
     * @return 对象存在时返回元数据，不存在时返回空
     */
    @Override
    public Optional<StoredObject> stat(String objectKey) {
        keyPolicy.requireOwned(objectKey);
        try {
            HeadObjectResponse head = client.headObject(
                    request -> request.bucket(template.bucket()).key(objectKey));
            String type = head.contentType() == null ? ObjectContentTypes.DEFAULT : head.contentType();
            return Optional.of(new StoredObject(storageRef, objectKey, head.contentLength(), type));
        } catch (NoSuchKeyException exception) {
            return Optional.empty();
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return Optional.empty();
            }
            throw translate("读取对象元数据失败：" + objectKey, exception);
        } catch (SdkException exception) {
            throw translate("读取对象元数据失败：" + objectKey, exception);
        }
    }

    /**
     * 删除对象；对象不存在时 S3 协议同样返回成功，可安全重试。
     *
     * @param objectKey 本项目前缀下的对象 key
     */
    @Override
    public void delete(String objectKey) {
        keyPolicy.requireOwned(objectKey);
        writable();
        try {
            client.deleteObject(request -> request.bucket(template.bucket()).key(objectKey));
        } catch (SdkException exception) {
            throw translate("删除对象失败：" + objectKey, exception);
        }
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
     * 签发限时下载地址，有效期截断到凭证剩余时间之内。
     *
     * @param objectKey 本项目前缀下的对象 key
     * @param ttl 期望有效期
     * @return 下载地址
     */
    @Override
    public URI presignGet(String objectKey, Duration ttl) {
        keyPolicy.requireOwned(objectKey);
        Duration duration = cap(ttl, readable());
        try {
            return presigner
                    .presignGetObject(request -> request.signatureDuration(duration)
                            .getObjectRequest(
                                    object -> object.bucket(template.bucket()).key(objectKey)))
                    .url()
                    .toURI();
        } catch (URISyntaxException exception) {
            throw new ObjectStorageException("预签名地址格式异常：" + objectKey, exception);
        } catch (SdkException exception) {
            throw translate("签发下载地址失败：" + objectKey, exception);
        }
    }

    /**
     * 签发限时直传地址；类型必须在平台白名单内，有效期截断到凭证剩余时间之内。
     *
     * @param objectKey 本项目前缀下的对象 key
     * @param contentType 上传时必须携带的 MIME 类型
     * @param ttl 期望有效期
     * @return 上传地址与必须携带的请求头
     */
    @Override
    public PresignedUpload presignPut(String objectKey, String contentType, Duration ttl) {
        keyPolicy.requireOwned(objectKey);
        StorageCredential current = writable();
        String type = ObjectContentTypes.normalize(contentType);
        ObjectContentTypes.requireAllowed(type, current.allowedContentTypes());
        Duration duration = cap(ttl, current);
        try {
            PresignedPutObjectRequest presigned = presigner.presignPutObject(request -> request.signatureDuration(
                            duration)
                    .putObjectRequest(object ->
                            object.bucket(template.bucket()).key(objectKey).contentType(type)));
            Map<String, String> headers = new LinkedHashMap<>();
            presigned.signedHeaders().forEach((name, values) -> {
                if (!"host".equals(name.toLowerCase(Locale.ROOT)) && !values.isEmpty()) {
                    headers.put(name, values.getFirst());
                }
            });
            return new PresignedUpload(presigned.url().toURI(), "PUT", headers, presigned.expiration());
        } catch (URISyntaxException exception) {
            throw new ObjectStorageException("预签名地址格式异常：" + objectKey, exception);
        } catch (SdkException exception) {
            throw translate("签发上传地址失败：" + objectKey, exception);
        }
    }

    /** 释放 SDK 客户端。 */
    @Override
    public void close() {
        client.close();
        presigner.close();
    }

    /**
     * 供 SDK 每次请求调用：从快照取当前凭证。
     *
     * @return 会话凭证
     */
    private AwsSessionCredentials currentSessionCredentials() {
        StorageCredential current = readable();
        return AwsSessionCredentials.create(current.accessKeyId(), current.accessKeySecret(), current.securityToken());
    }

    /**
     * 取当前未过期的凭证。
     *
     * @return 凭证
     */
    private StorageCredential readable() {
        return credentials
                .byRef(storageRef)
                .orElseThrow(
                        () -> new ObjectStorageException("存储凭证不可用：storageRef=" + storageRef + "，请检查平台绑定或 System 是否可达"));
    }

    /**
     * 取当前凭证并确认可写。
     *
     * @return 凭证
     */
    private StorageCredential writable() {
        StorageCredential current = readable();
        if (current.readOnly()) {
            throw new ObjectStorageException("存储绑定为只读，不能写入或删除：storageRef=" + storageRef);
        }
        return current;
    }

    /**
     * 执行上传并转换异常。
     *
     * @param objectKey 对象 key
     * @param type MIME 类型
     * @param length 字节数
     * @param body 请求体
     */
    private void upload(String objectKey, String type, long length, RequestBody body) {
        try {
            client.putObject(
                    request -> request.bucket(template.bucket())
                            .key(objectKey)
                            .contentType(type)
                            .contentLength(length),
                    body);
        } catch (SdkException exception) {
            throw translate("写入对象失败：" + objectKey, exception);
        }
    }

    /**
     * 把有效期截断到凭证剩余时间之内。
     *
     * @param ttl 期望有效期
     * @param current 当前凭证
     * @return 实际有效期
     */
    private Duration cap(Duration ttl, StorageCredential current) {
        Duration remaining =
                Duration.between(clock.instant(), current.expiresAt()).minus(PRESIGN_MARGIN);
        if (remaining.isNegative() || remaining.isZero()) {
            throw new ObjectStorageException("存储凭证即将过期，暂时无法签发地址：storageRef=" + storageRef);
        }
        return ttl.compareTo(remaining) > 0 ? remaining : ttl;
    }

    /**
     * 复制内容并在超过上限时立即中止。
     *
     * @param content 输入流
     * @param target 临时文件
     * @param limit 字节上限
     * @param objectKey 用于错误消息的对象 key
     * @return 实际字节数
     * @throws IOException 读写失败时抛出
     */
    private static long copyWithLimit(InputStream content, Path target, long limit, String objectKey)
            throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long written = 0;
        try (OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = content.read(buffer)) != -1) {
                written += read;
                if (written > limit) {
                    throw new ObjectStorageException("对象超过大小上限 " + limit + " 字节：" + objectKey);
                }
                out.write(buffer, 0, read);
            }
        }
        return written;
    }

    /**
     * 把 SDK 异常转换为存储异常；凭证不可用这类本地异常原样抛出，保留清晰的原因。
     *
     * @param message 面向开发者的说明
     * @param exception SDK 异常
     * @return 存储异常
     */
    private static ObjectStorageException translate(String message, SdkException exception) {
        if (exception instanceof SdkClientException && exception.getCause() instanceof ObjectStorageException cause) {
            return cause;
        }
        String detail = exception instanceof S3Exception s3
                ? "（HTTP " + s3.statusCode() + "，" + s3.awsErrorDetails().errorCode() + "）"
                : "（" + exception.getClass().getSimpleName() + "）";
        return new ObjectStorageException(message + detail, exception);
    }

    /**
     * 删除临时文件；失败只记录日志。
     *
     * @param path 临时文件，可为空
     */
    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("清理上传缓冲文件失败：{}", path.getFileName(), exception);
        }
    }
}
