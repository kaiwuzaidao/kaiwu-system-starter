package com.kaiwu.starter.storage.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaiwu.starter.storage.exception.ObjectNotFoundException;
import com.kaiwu.starter.storage.exception.ObjectStorageException;
import com.kaiwu.starter.storage.model.PendingFileUpload;
import com.kaiwu.starter.storage.model.StoredFile;
import com.kaiwu.starter.storage.model.StoredFileQuery;
import com.kaiwu.starter.storage.model.StoredFileStatus;
import com.kaiwu.starter.storage.service.ObjectKeyPolicy;
import com.kaiwu.starter.storage.service.StoredFileService;
import com.kaiwu.starter.storage.support.InMemoryStoredFileRepository;
import com.kaiwu.starter.storage.support.PresigningObjectStorage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/** 验证统一文件服务：登记、直传确认、挂载、软删除与孤儿清理（ADR 0041）。 */
class StoredFileServiceTest {

    private final InMemoryStoredFileRepository repository = new InMemoryStoredFileRepository();
    private final PresigningObjectStorage storage = new PresigningObjectStorage(true);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-29T08:00:00Z"));
    private StoredFileService service;

    /** 每个用例使用全新的仓库、存储与时钟。 */
    @BeforeEach
    void setUp() {
        service = build(storage);
    }

    /**
     * 按给定存储创建服务，单文件上限 1KB。
     *
     * @param target 使用的存储
     * @return 待测服务
     */
    private StoredFileService build(PresigningObjectStorage target) {
        return new StoredFileService(
                repository,
                target,
                ref -> target,
                new ObjectKeyPolicy("demo", clock),
                clock,
                1024,
                Duration.ofMinutes(15));
    }

    /**
     * 构造 UTF-8 内容流。
     *
     * @param text 文本
     * @return 内容流
     */
    private static ByteArrayInputStream text(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 新证据位于命名空间目录，重试复用原键，其他命名空间不能占用同一身份。 */
    @Test
    void reservesEvidenceUnderTrustedNamespace() {
        String id = "00000000-0000-0000-0000-000000000001";
        String digest = "a".repeat(64);
        var file = service.reserveEvidence("601", id, "workflow", "attempt", 3, digest);

        assertThat(file.objectKey()).startsWith("demo/evidence/601/2026/10/").endsWith(".bin");
        assertThat(service.reserveEvidence("601", id, "workflow", "attempt", 3, digest))
                .isEqualTo(file);
        assertThatThrownBy(() -> service.reserveEvidence("602", id, "workflow", "attempt", 3, digest))
                .isInstanceOf(ObjectStorageException.class);
    }

    /** 原重载保留原键布局及重试行为，新重载不能把历史记录视为有命名空间。 */
    @Test
    void retainsLegacyEvidenceKeyLayout() {
        String id = "00000000-0000-0000-0000-000000000001";
        String digest = "a".repeat(64);
        var file = service.reserveEvidence(id, "workflow", "attempt", 3, digest);

        assertThat(file.objectKey()).matches("demo/evidence/2026/10/[0-9a-f-]{36}\\.bin");
        assertThat(service.reserveEvidence(id, "workflow", "attempt", 3, digest))
                .isEqualTo(file);
        assertThatThrownBy(() -> service.reserveEvidence("2026", id, "workflow", "attempt", 3, digest))
                .isInstanceOf(ObjectStorageException.class);
    }

    /** 空值、路径分隔符、转义字符及超长命名空间均在登记前拒绝。 */
    @Test
    void rejectsUnsafeEvidenceNamespaces() {
        for (String namespace : new String[] {null, "", "..", "a/b", "a\\b", "%2f", "a b", "中文", "a".repeat(65)}) {
            assertThatThrownBy(() -> service.reserveEvidence(
                            namespace,
                            "00000000-0000-0000-0000-000000000001",
                            "workflow",
                            "attempt",
                            3,
                            "a".repeat(64)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(repository.count()).isZero();
    }

    /** 后端中转上传：登记为生效记录，保存摘要，原始文件名只取末段，key 不含用户文件名。 */
    @Test
    void uploadRegistersActiveFileWithDigestAndSafeName() {
        StoredFile file = service.upload("invoice", "C:\\fakepath\\发票.PDF", "application/pdf", text("hello"), 5, "7");

        assertThat(file.status()).isEqualTo(StoredFileStatus.ACTIVE);
        assertThat(file.originalName()).isEqualTo("发票.PDF");
        assertThat(file.sizeBytes()).isEqualTo(5);
        assertThat(file.sha256()).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(file.objectKey())
                .startsWith("demo/invoice/2026/10/")
                .endsWith(".pdf")
                .doesNotContain("发票");
        assertThat(storage.contains(file.objectKey())).isTrue();
    }

    /** 登记异常不能证明数据库未提交，保留真实对象并维持原失败回执供调用方核对。 */
    @Test
    void uploadKeepsObjectWhenRegistrationIsUncertain() {
        var target = Mockito.spy(storage);
        service = build(target);
        repository.failNextInsert = true;

        assertThatThrownBy(() -> service.upload("invoice", "a.txt", "text/plain", text("x"), 1, "7"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(repository.count()).isZero();
        var key = ArgumentCaptor.forClass(String.class);
        Mockito.verify(target)
                .put(key.capture(), ArgumentMatchers.any(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyString());
        Mockito.verify(target, Mockito.never()).delete(ArgumentMatchers.anyString());
        assertThat(storage.stat(key.getValue())).isPresent();
    }

    /** 挂到业务记录后可按业务 ID 过滤，列表只含生效记录并按时间倒序。 */
    @Test
    void bindAndPageFilterByBusinessRecord() {
        StoredFile first = service.upload("invoice", "a.txt", "text/plain", text("a"), 1, "7");
        clock.advance(Duration.ofMinutes(1));
        StoredFile second = service.upload("invoice", "b.txt", "text/plain", text("b"), 1, "8");
        service.bind(first.id(), "R-1");

        assertThat(service.page(new StoredFileQuery(null, null, "R-1", null), 1, 20)
                        .records())
                .extracting(StoredFile::id)
                .containsExactly(first.id());
        assertThat(service.page(new StoredFileQuery(null, "invoice", null, null), 1, 20)
                        .records())
                .extracting(StoredFile::id)
                .containsExactly(second.id(), first.id());
        assertThat(service.page(new StoredFileQuery("b.", null, null, null), 1, 20)
                        .total())
                .isEqualTo(1);
    }

    /** 直传：确认前对象不存在要拒绝；上传后确认生效；他人不能替登记人确认。 */
    @Test
    void directUploadMustBeConfirmedByOwnerAfterObjectExists() {
        PendingFileUpload pending = service.prepareDirectUpload("invoice", "a.png", "image/png", 4, "7");
        String id = pending.file().id();
        assertThat(pending.file().status()).isEqualTo(StoredFileStatus.PENDING);
        assertThat(service.find(id)).isEmpty();

        assertThatThrownBy(() -> service.confirm(id, "7")).isInstanceOf(ObjectStorageException.class);

        storage.simulateDirectUpload(pending.file().objectKey(), new byte[] {1, 2, 3, 4}, "image/png");
        assertThatThrownBy(() -> service.confirm(id, "other")).isInstanceOf(ObjectNotFoundException.class);

        StoredFile confirmed = service.confirm(id, "7");
        assertThat(confirmed.status()).isEqualTo(StoredFileStatus.ACTIVE);
        assertThat(service.find(id)).isPresent();
    }

    /** 声明的大小或类型与对象实际不符时拒绝确认，记录保持待确认。 */
    @Test
    void confirmRejectsMismatchedSizeOrType() {
        PendingFileUpload pending = service.prepareDirectUpload("invoice", "a.png", "image/png", 4, "7");
        storage.simulateDirectUpload(pending.file().objectKey(), new byte[] {1, 2}, "image/png");
        assertThatThrownBy(() -> service.confirm(pending.file().id(), "7")).isInstanceOf(ObjectStorageException.class);

        storage.simulateDirectUpload(pending.file().objectKey(), new byte[] {1, 2, 3, 4}, "text/html");
        assertThatThrownBy(() -> service.confirm(pending.file().id(), "7")).isInstanceOf(ObjectStorageException.class);
        assertThat(repository.find(pending.file().id()).orElseThrow().status()).isEqualTo(StoredFileStatus.PENDING);
    }

    /** 直传的前置条件：存储支持预签名，且声明大小在上限之内。 */
    @Test
    void directUploadValidatesStorageAndSize() {
        assertThatThrownBy(() -> service.prepareDirectUpload("invoice", "a.png", "image/png", 0, "7"))
                .isInstanceOf(ObjectStorageException.class);
        assertThatThrownBy(() -> service.prepareDirectUpload("invoice", "a.png", "image/png", 2048, "7"))
                .isInstanceOf(ObjectStorageException.class);
        StoredFileService noPresign = build(new PresigningObjectStorage(false));
        assertThatThrownBy(() -> noPresign.prepareDirectUpload("invoice", "a.png", "image/png", 4, "7"))
                .isInstanceOf(ObjectStorageException.class);
    }

    /** 软删除立即不可见，保留期内对象仍在，过期后清理才删除对象与记录。 */
    @Test
    void softDeleteHidesFileAndPurgeRemovesAfterRetention() {
        StoredFile file = service.upload("invoice", "a.txt", "text/plain", text("a"), 1, "7");
        service.delete(file.id());

        assertThat(service.find(file.id())).isEmpty();
        assertThat(service.page(new StoredFileQuery(null, null, null, null), 1, 20)
                        .total())
                .isZero();
        assertThatThrownBy(() -> service.delete(file.id())).isInstanceOf(ObjectNotFoundException.class);

        clock.advance(Duration.ofDays(6));
        assertThat(service.purgeExpired(Duration.ofHours(24), Duration.ofDays(7)))
                .isZero();
        assertThat(storage.contains(file.objectKey())).isTrue();

        clock.advance(Duration.ofDays(2));
        assertThat(service.purgeExpired(Duration.ofHours(24), Duration.ofDays(7)))
                .isEqualTo(1);
        assertThat(storage.contains(file.objectKey())).isFalse();
        assertThat(repository.count()).isZero();
    }

    /** 超时未确认的直传记录视为孤儿，清理时连对象一起回收。 */
    @Test
    void purgeRemovesUnconfirmedDirectUploads() {
        PendingFileUpload pending = service.prepareDirectUpload("invoice", "a.png", "image/png", 4, "7");
        storage.simulateDirectUpload(pending.file().objectKey(), new byte[] {1, 2, 3, 4}, "image/png");

        clock.advance(Duration.ofHours(25));

        assertThat(service.purgeExpired(Duration.ofHours(24), Duration.ofDays(7)))
                .isEqualTo(1);
        assertThat(storage.contains(pending.file().objectKey())).isFalse();
    }

    /** 支持预签名时给直连地址，不支持时返回空让调用方走中转。 */
    @Test
    void presignDownloadFallsBackWhenStorageCannotSign() {
        StoredFile file = service.upload("invoice", "a.txt", "text/plain", text("a"), 1, "7");
        assertThat(service.presignDownload(file, Duration.ofMinutes(5))).isPresent();

        StoredFileService noPresign = build(new PresigningObjectStorage(false));
        assertThat(noPresign.presignDownload(file, Duration.ofMinutes(5))).isEmpty();
    }

    /** 可手动推进的时钟，用来验证保留期。 */
    private static final class MutableClock extends Clock {

        private Instant now;

        /**
         * 创建时钟。
         *
         * @param start 起始时刻
         */
        MutableClock(Instant start) {
            this.now = start;
        }

        /**
         * 向前推进时间。
         *
         * @param duration 推进的时长
         */
        void advance(Duration duration) {
            now = now.plus(duration);
        }

        /** 固定使用 UTC。 */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /** 时区固定，返回自身。 */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /** 返回可推进的当前时刻。 */
        @Override
        public Instant instant() {
            return now;
        }
    }
}
