package com.kaiwu.starter.storage.adapter;

import com.kaiwu.starter.storage.exception.ObjectNotFoundException;
import com.kaiwu.starter.storage.exception.ObjectStorageException;
import com.kaiwu.starter.storage.model.PresignedUpload;
import com.kaiwu.starter.storage.model.StoredObject;
import com.kaiwu.starter.storage.service.ObjectContentTypes;
import com.kaiwu.starter.storage.service.ObjectKeyPolicy;
import com.kaiwu.starter.storage.service.ObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把对象保存在本机目录的存储实现，用于本地开发、测试和体验版单容器部署。
 *
 * <p>目录结构为 {@code {root}/objects/{key}} 保存内容、{@code {root}/meta/{key}} 保存 MIME 类型。
 * 写入先落到同目录临时文件，校验长度后再原子替换，失败时不会留下半个对象。
 * 本实现不支持预签名 URL，业务应走后端中转上传和下载。
 *
 * <p>多副本部署时各实例必须挂载同一份共享目录，否则会读不到其他实例写入的对象；
 * 生产环境应使用对象存储而不是本实现。
 */
public final class LocalDiskObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalDiskObjectStorage.class);

    /** 本地磁盘存储的引用标识，写入业务记录的 {@code storage_ref}。 */
    public static final String STORAGE_REF = "local";

    private static final int COPY_BUFFER_BYTES = 8192;

    private final Path objectsRoot;
    private final Path metaRoot;
    private final ObjectKeyPolicy keyPolicy;
    private final long maxObjectBytes;

    /**
     * 创建本地磁盘存储，并确保根目录可用。
     *
     * @param rootDirectory 存储根目录，不存在时自动创建
     * @param keyPolicy 本项目的 key 策略，用于拒绝越界 key
     * @param maxObjectBytes 单个对象的字节上限，必须为正数
     * @throws IllegalStateException 上限非法或根目录无法创建时抛出，应用启动随之失败
     */
    public LocalDiskObjectStorage(Path rootDirectory, ObjectKeyPolicy keyPolicy, long maxObjectBytes) {
        if (maxObjectBytes <= 0) {
            throw new IllegalStateException("kaiwu.starter.storage.max-object-size 必须大于 0");
        }
        Path root = rootDirectory.toAbsolutePath().normalize();
        this.objectsRoot = root.resolve("objects");
        this.metaRoot = root.resolve("meta");
        this.keyPolicy = keyPolicy;
        this.maxObjectBytes = maxObjectBytes;
        try {
            Files.createDirectories(objectsRoot);
            Files.createDirectories(metaRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建本地对象存储目录", exception);
        }
    }

    /**
     * 返回固定的本地存储引用。
     *
     * @return {@link #STORAGE_REF}
     */
    @Override
    public String storageRef() {
        return STORAGE_REF;
    }

    /**
     * 先写入临时文件并校验长度与上限，再原子替换目标对象；任何失败都会清理临时文件。
     *
     * @param objectKey 本项目前缀下的对象 key
     * @param content 对象内容，由调用方负责关闭
     * @param contentLength 声明的字节数；未知时传 {@code -1}
     * @param contentType MIME 类型；为空时按 {@code application/octet-stream} 保存
     * @return 写入后的对象元数据
     */
    @Override
    public StoredObject put(String objectKey, InputStream content, long contentLength, String contentType) {
        Path target = resolve(objectsRoot, objectKey);
        String type = ObjectContentTypes.normalize(contentType);
        if (contentLength > maxObjectBytes) {
            throw new ObjectStorageException("对象超过大小上限 " + maxObjectBytes + " 字节：" + objectKey);
        }
        Path tempObject = null;
        Path tempMeta = null;
        try {
            Files.createDirectories(target.getParent());
            tempObject = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            long written = copyWithLimit(content, tempObject, objectKey);
            if (contentLength >= 0 && written != contentLength) {
                throw new ObjectStorageException("对象实际长度与声明长度不一致：" + objectKey);
            }
            Path metaTarget = resolve(metaRoot, objectKey);
            Files.createDirectories(metaTarget.getParent());
            tempMeta = Files.createTempFile(metaTarget.getParent(), ".meta-", ".tmp");
            Files.writeString(tempMeta, type, StandardCharsets.UTF_8);
            moveAtomically(tempObject, target);
            tempObject = null;
            moveAtomically(tempMeta, metaTarget);
            tempMeta = null;
            return new StoredObject(STORAGE_REF, objectKey, written, type);
        } catch (IOException exception) {
            throw new ObjectStorageException("写入本地对象失败：" + objectKey, exception);
        } finally {
            deleteQuietly(tempObject);
            deleteQuietly(tempMeta);
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
        Path target = resolve(objectsRoot, objectKey);
        try {
            return Files.newInputStream(target);
        } catch (NoSuchFileException exception) {
            throw new ObjectNotFoundException(objectKey);
        } catch (IOException exception) {
            throw new ObjectStorageException("读取本地对象失败：" + objectKey, exception);
        }
    }

    /**
     * 读取对象大小与 MIME 类型；元数据文件缺失时按 {@code application/octet-stream} 返回。
     *
     * @param objectKey 本项目前缀下的对象 key
     * @return 对象存在时返回元数据，不存在时返回空
     */
    @Override
    public Optional<StoredObject> stat(String objectKey) {
        Path target = resolve(objectsRoot, objectKey);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            long size = Files.size(target);
            Path meta = resolve(metaRoot, objectKey);
            String type = Files.isRegularFile(meta)
                    ? Files.readString(meta, StandardCharsets.UTF_8).trim()
                    : ObjectContentTypes.DEFAULT;
            return Optional.of(new StoredObject(STORAGE_REF, objectKey, size, type));
        } catch (NoSuchFileException exception) {
            return Optional.empty();
        } catch (IOException exception) {
            throw new ObjectStorageException("读取本地对象元数据失败：" + objectKey, exception);
        }
    }

    /**
     * 删除对象及其元数据；不存在时直接返回，可安全重试。
     *
     * @param objectKey 本项目前缀下的对象 key
     */
    @Override
    public void delete(String objectKey) {
        Path target = resolve(objectsRoot, objectKey);
        Path meta = resolve(metaRoot, objectKey);
        try {
            Files.deleteIfExists(target);
            Files.deleteIfExists(meta);
        } catch (IOException exception) {
            throw new ObjectStorageException("删除本地对象失败：" + objectKey, exception);
        }
    }

    /**
     * 本地磁盘没有可供浏览器直连的签名地址。
     *
     * @return 恒为 {@code false}
     */
    @Override
    public boolean supportsPresignedUrls() {
        return false;
    }

    /**
     * 本地磁盘不支持预签名下载。
     *
     * @param objectKey 对象 key
     * @param ttl 期望有效期
     * @return 不会正常返回
     * @throws ObjectStorageException 总是抛出，提示改用后端中转下载
     */
    @Override
    public URI presignGet(String objectKey, Duration ttl) {
        throw new ObjectStorageException("本地存储不支持预签名 URL，请改用后端中转下载");
    }

    /**
     * 本地磁盘不支持预签名直传。
     *
     * @param objectKey 对象 key
     * @param contentType 上传时的 MIME 类型
     * @param ttl 期望有效期
     * @return 不会正常返回
     * @throws ObjectStorageException 总是抛出，提示改用后端中转上传
     */
    @Override
    public PresignedUpload presignPut(String objectKey, String contentType, Duration ttl) {
        throw new ObjectStorageException("本地存储不支持预签名 URL，请改用后端中转上传");
    }

    /**
     * 校验 key 归属后解析到指定根目录下，并再次确认没有越出根目录。
     *
     * @param base 内容或元数据根目录
     * @param objectKey 对象 key
     * @return 规范化后的文件路径
     */
    private Path resolve(Path base, String objectKey) {
        Path path = base.resolve(keyPolicy.requireOwned(objectKey)).normalize();
        if (!path.startsWith(base)) {
            throw new ObjectStorageException("对象 key 越出存储目录：" + objectKey);
        }
        return path;
    }

    /**
     * 复制内容并在超过上限时立即中止，避免超大上传先写满磁盘再被拒绝。
     *
     * @param content 输入流
     * @param target 临时文件
     * @param objectKey 用于错误消息的对象 key
     * @return 实际写入的字节数
     * @throws IOException 读写失败时抛出
     */
    private long copyWithLimit(InputStream content, Path target, String objectKey) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long written = 0;
        try (OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = content.read(buffer)) != -1) {
                written += read;
                if (written > maxObjectBytes) {
                    throw new ObjectStorageException("对象超过大小上限 " + maxObjectBytes + " 字节：" + objectKey);
                }
                out.write(buffer, 0, read);
            }
        }
        return written;
    }

    /**
     * 原子替换目标文件；文件系统不支持原子移动时退回普通替换。
     *
     * @param source 已写完的临时文件
     * @param target 目标文件
     * @throws IOException 移动失败时抛出
     */
    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 清理失败写入遗留的临时文件；清理本身失败只记录日志，不覆盖原始异常。
     *
     * @param path 临时文件，为空时跳过
     */
    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("清理本地对象存储临时文件失败：{}", path.getFileName(), exception);
        }
    }
}
