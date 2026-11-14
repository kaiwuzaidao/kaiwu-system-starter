package com.kaiwu.starter.storage.service;

import com.kaiwu.starter.storage.exception.ObjectNotFoundException;
import com.kaiwu.starter.storage.exception.ObjectStorageException;
import com.kaiwu.starter.storage.model.PendingFileUpload;
import com.kaiwu.starter.storage.model.PresignedUpload;
import com.kaiwu.starter.storage.model.StoredFile;
import com.kaiwu.starter.storage.model.StoredFilePage;
import com.kaiwu.starter.storage.model.StoredFileQuery;
import com.kaiwu.starter.storage.model.StoredFileStatus;
import com.kaiwu.starter.storage.model.StoredObject;
import com.kaiwu.starter.storage.repository.StoredFileRepository;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 项目内统一文件服务（ADR 0041）：把对象写入存储的同时登记一条文件记录，业务表只保存文件 ID。
 *
 * <p>本服务不做业务权限判断。业务调用上传、挂载、下载前必须自己确认当前用户有权访问对应业务记录；
 * 文件管理页的列表、下载、删除由 {@code StoredFileController} 的权限码把关。</p>
 */
public class StoredFileService {

    private static final int MAX_NAME_LENGTH = 255;
    private static final int PURGE_BATCH = 200;
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final StoredFileRepository repository;
    private final ObjectStorage storage;
    private final ObjectStorageResolver resolver;
    private final ObjectKeyPolicy keyPolicy;
    private final Clock clock;
    private final long maxObjectBytes;
    private final Duration uploadTtl;

    /**
     * 创建服务。
     *
     * @param repository 文件记录仓库
     * @param storage 当前生效的存储，所有新写入都走它
     * @param resolver 按记录里的存储引用找回存储，读取与删除时使用
     * @param keyPolicy 本项目的 key 策略
     * @param clock 时钟
     * @param maxObjectBytes 单个文件的字节上限，直传声明的大小超过它即拒绝
     * @param uploadTtl 直传地址的有效期
     */
    public StoredFileService(
            StoredFileRepository repository,
            ObjectStorage storage,
            ObjectStorageResolver resolver,
            ObjectKeyPolicy keyPolicy,
            Clock clock,
            long maxObjectBytes,
            Duration uploadTtl) {
        this.repository = repository;
        this.storage = storage;
        this.resolver = resolver;
        this.keyPolicy = keyPolicy;
        this.clock = clock;
        this.maxObjectBytes = maxObjectBytes;
        this.uploadTtl = uploadTtl;
    }

    /**
     * 后端中转上传：写入对象并登记为生效记录。登记结果不确定时保留对象供核对。
     *
     * @param bizType 业务类型，同时是 object key 的一段，规则见 {@link ObjectKeyPolicy#generate}
     * @param originalName 原始文件名
     * @param contentType MIME 类型，为空时按二进制流保存
     * @param content 文件内容，由调用方关闭
     * @param contentLength 声明的字节数，未知时传 {@code -1}
     * @param uploaderId 上传人用户 ID
     * @return 生效的文件记录
     * @throws ObjectStorageException 业务类型非法、超过大小上限或写入失败时抛出
     */
    public StoredFile upload(
            String bizType,
            String originalName,
            String contentType,
            InputStream content,
            long contentLength,
            String uploaderId) {
        String key = keyPolicy.generate(bizType, originalName);
        MessageDigest digest = newDigest();
        StoredObject stored;
        try (DigestInputStream hashed = new DigestInputStream(content, digest)) {
            stored = storage.put(key, hashed, contentLength, normalizeContentType(contentType));
        } catch (IOException exception) {
            throw new ObjectStorageException("读取上传内容失败", exception);
        }
        LocalDateTime now = now();
        StoredFile file = new StoredFile(
                UUID.randomUUID().toString(),
                stored.storageRef(),
                stored.objectKey(),
                displayName(originalName),
                stored.contentType(),
                stored.size(),
                HexFormat.of().formatHex(digest.digest()),
                bizType,
                null,
                StoredFileStatus.ACTIVE,
                requireText(uploaderId, "上传人"),
                now,
                now,
                null);
        try {
            repository.insert(file);
        } catch (RuntimeException exception) {
            // insert可能已提交但回执丢失；异常本身不能证明数据库未引用。
            // 保留稳定文件/存储身份供维护核对，不记录正文、凭据、URL或原始异常。
            LoggerFactory.getLogger(StoredFileService.class)
                    .warn(
                            "stored_file_registration_uncertain fileId={} storageRef={} objectKey={}",
                            file.id(),
                            file.storageRef(),
                            file.objectKey());
            throw exception;
        }
        return file;
    }

    /** 为可信后台任务预留文件身份；不接受用户Context作为后台身份。 */
    public StoredFile reserveEvidence(String id, String ownerService, String reference, long size, String sha256) {
        return reserveEvidenceUnder(null, id, ownerService, reference, size, sha256);
    }

    /**
     * 为可信后台任务在指定命名空间预留文件身份。
     * 调用方须从已校验的归属取命名空间，不得透传用户输入。重试须使用相同命名空间。
     */
    public StoredFile reserveEvidence(
            String namespace, String id, String ownerService, String reference, long size, String sha256) {
        return reserveEvidenceUnder(keyPolicy.evidencePrefix(namespace), id, ownerService, reference, size, sha256);
    }

    /** 校验预留身份，复用同一身份的历史记录或写入新记录。 */
    private StoredFile reserveEvidenceUnder(
            String prefix, String id, String ownerService, String reference, long size, String sha256) {
        if (id == null
                || !id.matches("[0-9a-f-]{36}")
                || ownerService == null
                || !ownerService.matches("[A-Za-z0-9_-]{1,56}")
                || reference == null
                || !reference.matches("[A-Za-z0-9._:-]{1,64}")
                || size < 1
                || size > maxObjectBytes
                || sha256 == null
                || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid evidence identity");
        }
        var existing = repository.find(id);
        if (existing.isPresent()) {
            var file = existing.get();
            if (!file.uploaderId().equals("system:" + ownerService)
                    || !reference.equals(file.bizId())
                    || file.sizeBytes() != size
                    || !sha256.equals(file.sha256())
                    || (prefix != null && !keyPolicy.isEvidenceUnder(prefix, file.objectKey()))) {
                throw new ObjectStorageException("evidence identity conflict");
            }
            return file;
        }
        var reserved = new StoredFile(
                id,
                storage.storageRef(),
                prefix == null ? keyPolicy.generate("evidence", "evidence.bin") : keyPolicy.generateEvidence(prefix),
                "evidence.bin",
                "application/octet-stream",
                size,
                sha256,
                "evidence",
                reference,
                StoredFileStatus.RESERVED,
                "system:" + ownerService,
                now(),
                null,
                null);
        repository.insert(reserved); // 写对象前先提交身份；回执丢失时调用者用预定id重读，绝不盲删。
        return reserved;
    }

    /** 对预留文件最多发起一次写入；后续调用只核对原内容，不覆盖对象。 */
    public StoredFile uploadEvidence(String id, InputStream content) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("evidence IO must be outside transaction");
        }
        var file = repository.find(id).orElseThrow(() -> new ObjectNotFoundException(id));
        if (!file.uploaderId().startsWith("system:") || file.sha256() == null || file.sizeBytes() > maxObjectBytes) {
            throw new ObjectStorageException("invalid evidence reservation");
        }
        ObjectStorage owner = resolver.forRef(file.storageRef());
        if (repository.claimEvidenceUpload(id)) {
            owner.put(file.objectKey(), content, file.sizeBytes(), file.contentType());
        }
        // 任何重试只能核对，不能重复PUT；缺失/部分对象保持UPLOADING供新尝试或人工核对。
        try (InputStream input = owner.get(file.objectKey())) {
            MessageDigest digest = newDigest();
            byte[] buffer = new byte[8192];
            long size = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                size += count;
                if (size > file.sizeBytes()) {
                    throw new ObjectStorageException("evidence integrity failure");
                }
                digest.update(buffer, 0, count);
            }
            if (size != file.sizeBytes()
                    || !HexFormat.of().formatHex(digest.digest()).equals(file.sha256())) {
                throw new ObjectStorageException("evidence integrity failure");
            }
        } catch (IOException failure) {
            throw new ObjectStorageException("evidence read failed");
        }
        if (!repository.completeEvidenceUpload(id, now())) {
            throw new ObjectStorageException("evidence confirmation rejected");
        }
        return repository.find(id).orElseThrow(() -> new ObjectNotFoundException(id));
    }

    /**
     * 直传第一步：登记待确认记录并签发上传地址。前端上传完成后必须调用 {@link #confirm}，文件才生效。
     *
     * @param bizType 业务类型
     * @param originalName 原始文件名
     * @param contentType 上传时必须携带的 MIME 类型
     * @param declaredSize 声明的字节数，确认时会与对象实际大小核对
     * @param uploaderId 上传人用户 ID
     * @return 待确认记录与上传地址
     * @throws ObjectStorageException 当前存储不支持预签名、大小非法或超限时抛出
     */
    public PendingFileUpload prepareDirectUpload(
            String bizType, String originalName, String contentType, long declaredSize, String uploaderId) {
        if (!storage.supportsPresignedUrls()) {
            throw new ObjectStorageException("当前存储不支持直传，请改用后端中转上传");
        }
        if (declaredSize <= 0 || declaredSize > maxObjectBytes) {
            throw new ObjectStorageException("文件大小必须在 1 到 " + maxObjectBytes + " 字节之间");
        }
        String type = normalizeContentType(contentType);
        String key = keyPolicy.generate(bizType, originalName);
        PresignedUpload upload = storage.presignPut(key, type, uploadTtl);
        StoredFile pending = new StoredFile(
                UUID.randomUUID().toString(),
                storage.storageRef(),
                key,
                displayName(originalName),
                type,
                declaredSize,
                null,
                bizType,
                null,
                StoredFileStatus.PENDING,
                requireText(uploaderId, "上传人"),
                now(),
                null,
                null);
        repository.insert(pending);
        return new PendingFileUpload(pending, upload);
    }

    /**
     * 直传第二步：复核对象已存在且大小、类型与声明一致后置为生效。只有登记人能确认。
     *
     * @param fileId 文件 ID
     * @param operatorId 当前用户 ID
     * @return 生效后的记录
     * @throws ObjectNotFoundException 记录不存在或不是待确认状态时抛出
     * @throws ObjectStorageException 对象尚未上传，或大小、类型与声明不符时抛出
     */
    public StoredFile confirm(String fileId, String operatorId) {
        StoredFile pending = repository
                .find(fileId)
                .filter(file -> file.status() == StoredFileStatus.PENDING)
                .filter(file -> file.uploaderId().equals(operatorId))
                .orElseThrow(() -> new ObjectNotFoundException(fileId));
        StoredObject actual = resolver.forRef(pending.storageRef())
                .stat(pending.objectKey())
                .orElseThrow(() -> new ObjectStorageException("对象尚未上传完成"));
        if (actual.size() != pending.sizeBytes() || !sameType(actual.contentType(), pending.contentType())) {
            throw new ObjectStorageException("上传的对象与声明的大小或类型不一致");
        }
        LocalDateTime now = now();
        if (!repository.activate(pending.id(), actual.size(), actual.contentType(), now)) {
            throw new ObjectNotFoundException(fileId);
        }
        return repository.find(fileId).orElseThrow(() -> new ObjectNotFoundException(fileId));
    }

    /**
     * 把生效中的文件挂到业务记录上。业务记录删除时由业务决定是连带 {@link #delete} 还是保留文件。
     *
     * @param fileId 文件 ID
     * @param bizId 业务记录 ID
     * @throws ObjectNotFoundException 文件不存在或不是生效状态时抛出
     */
    public void bind(String fileId, String bizId) {
        if (!repository.bind(fileId, requireText(bizId, "业务记录"))) {
            throw new ObjectNotFoundException(fileId);
        }
    }

    /** 业务授权后建立不可自动释放的证据保留引用；重复调用幂等，不能替代所属服务授权。 */
    public void retain(String fileId, String ownerService, String reference) {
        if (!repository.retain(fileId, ownerService, reference)) {
            throw new ObjectNotFoundException(fileId);
        }
    }

    /** 仅可信维护流程确认业务未引用且不存在迟到提交后调用；不向普通管理接口暴露。 */
    public boolean release(String fileId, String ownerService, String reference) {
        return repository.release(fileId, ownerService, reference);
    }

    /**
     * 查询生效中的文件。
     *
     * @param fileId 文件 ID
     * @return 记录，不存在或已删除时为空
     */
    public Optional<StoredFile> find(String fileId) {
        return repository.find(fileId).filter(file -> file.status() == StoredFileStatus.ACTIVE);
    }

    /**
     * 分页查询生效中的文件。
     *
     * @param query 筛选条件
     * @param current 页码，从 1 开始
     * @param size 每页条数，上限 100
     * @return 分页结果
     */
    public StoredFilePage page(StoredFileQuery query, long current, long size) {
        return repository.page(query, current, size);
    }

    /**
     * 签发限时下载地址；存储不支持预签名时返回空，调用方应改走 {@link #open}。
     *
     * @param file 生效中的文件记录
     * @param ttl 期望有效期，实现可能缩短
     * @return 下载地址，不支持预签名时为空
     */
    public Optional<URI> presignDownload(StoredFile file, Duration ttl) {
        ObjectStorage owner = resolver.forRef(file.storageRef());
        if (!owner.supportsPresignedUrls()) {
            return Optional.empty();
        }
        return Optional.of(owner.presignGet(file.objectKey(), ttl));
    }

    /**
     * 打开文件内容，用于不支持预签名时的后端中转下载。
     *
     * @param file 生效中的文件记录
     * @return 内容流，调用方必须关闭
     * @throws ObjectNotFoundException 对象不存在时抛出
     */
    public InputStream open(StoredFile file) {
        return resolver.forRef(file.storageRef()).get(file.objectKey());
    }

    /**
     * 软删除：记录立即不可见，对象在保留期后才真正删除，期间误删可由运维恢复。
     *
     * @param fileId 文件 ID
     * @throws ObjectNotFoundException 记录不存在或已删除时抛出
     */
    public void delete(String fileId) {
        if (!repository.markDeleted(fileId, now())) {
            throw new ObjectNotFoundException(fileId);
        }
    }

    /**
     * 清理孤儿与过期删除：超时未确认的直传记录、超过保留期的软删除记录，先删对象再删记录。
     * 对象删除失败的记录保留到下一轮，不会出现记录没了对象还在的孤儿。
     *
     * @param pendingTtl 待确认记录的最长保留时间
     * @param deletedRetention 软删除记录的保留时间
     * @return 本轮清理的记录数
     */
    public int purgeExpired(Duration pendingTtl, Duration deletedRetention) {
        LocalDateTime now = now();
        int purged = purgeBatch(repository.findExpired(StoredFileStatus.PENDING, now.minus(pendingTtl), PURGE_BATCH));
        purged +=
                purgeBatch(repository.findExpired(StoredFileStatus.DELETED, now.minus(deletedRetention), PURGE_BATCH));
        return purged;
    }

    /**
     * 逐条清理一批记录，单条失败只跳过。
     *
     * @param files 待清理记录
     * @return 清理成功的条数
     */
    private int purgeBatch(List<StoredFile> files) {
        int purged = 0;
        for (StoredFile file : files) {
            try {
                if (!repository.claimPurge(file)) {
                    continue;
                }
                resolver.forRef(file.storageRef()).delete(file.objectKey());
                repository.purge(file.id());
                purged++;
            } catch (RuntimeException exception) {
                // 存储暂不可用或引用已停用：保留记录，下一轮再试。
            }
        }
        return purged;
    }

    /**
     * 当前本地时间。
     *
     * @return 按服务时钟取得的时间
     */
    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /**
     * 创建 SHA-256 摘要器。
     *
     * @return 摘要器
     */
    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    /**
     * 规整 MIME 类型。
     *
     * @param contentType 原始类型，可为空
     * @return 去空白后的类型，空时返回二进制流
     */
    private static String normalizeContentType(String contentType) {
        return contentType == null || contentType.isBlank() ? DEFAULT_CONTENT_TYPE : contentType.trim();
    }

    /**
     * 比较两个 MIME 类型，忽略参数（如 charset）与大小写。
     *
     * @param actual 对象上读到的类型
     * @param declared 登记时声明的类型
     * @return 主类型相同时为 {@code true}
     */
    private static boolean sameType(String actual, String declared) {
        return baseType(actual).equals(baseType(declared));
    }

    /**
     * 取 MIME 主类型。
     *
     * @param value 可能带参数的类型
     * @return 小写的主类型
     */
    private static String baseType(String value) {
        if (value == null) {
            return DEFAULT_CONTENT_TYPE;
        }
        int semicolon = value.indexOf(';');
        String base = semicolon < 0 ? value : value.substring(0, semicolon);
        return base.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 生成用于展示的文件名，去掉路径并限制长度。
     *
     * @param originalName 用户上传的原始文件名
     * @return 非空的展示名
     */
    private static String displayName(String originalName) {
        String name = originalName == null ? "" : originalName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.isEmpty()) {
            return "unnamed";
        }
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }

    /**
     * 要求字段非空。
     *
     * @param value 待检查的值
     * @param label 字段含义，用于错误信息
     * @return 去空白后的值
     * @throws IllegalArgumentException 为空时抛出
     */
    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.trim();
    }
}
