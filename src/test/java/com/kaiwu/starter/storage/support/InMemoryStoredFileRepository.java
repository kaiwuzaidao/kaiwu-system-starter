package com.kaiwu.starter.storage.support;

import com.kaiwu.starter.storage.model.StoredFile;
import com.kaiwu.starter.storage.model.StoredFilePage;
import com.kaiwu.starter.storage.model.StoredFileQuery;
import com.kaiwu.starter.storage.model.StoredFileStatus;
import com.kaiwu.starter.storage.repository.StoredFileRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 测试用的内存文件仓库，语义与 JDBC 实现保持一致，可注入插入失败。 */
public class InMemoryStoredFileRepository implements StoredFileRepository {

    private final Map<String, StoredFile> rows = new LinkedHashMap<>();

    /** 为 {@code true} 时下一次 {@link #insert} 抛出异常，用于验证失败回收。 */
    public boolean failNextInsert;

    /** 保存记录，可按开关模拟失败。 */
    @Override
    public void insert(StoredFile file) {
        if (failNextInsert) {
            failNextInsert = false;
            throw new IllegalStateException("insert failed");
        }
        rows.put(file.id(), file);
    }

    /** 按 ID 查询。 */
    @Override
    public Optional<StoredFile> find(String id) {
        return Optional.ofNullable(rows.get(id));
    }

    /** 待确认转生效。 */
    @Override
    public boolean activate(String id, long sizeBytes, String contentType, LocalDateTime confirmedAt) {
        StoredFile row = rows.get(id);
        if (row == null || row.status() != StoredFileStatus.PENDING) {
            return false;
        }
        rows.put(id, copy(row, row.bizId(), StoredFileStatus.ACTIVE, sizeBytes, contentType, confirmedAt, null));
        return true;
    }

    /** 挂到业务记录。 */
    @Override
    public boolean bind(String id, String bizId) {
        StoredFile row = rows.get(id);
        if (row == null || row.status() != StoredFileStatus.ACTIVE) {
            return false;
        }
        rows.put(id, copy(row, bizId, row.status(), row.sizeBytes(), row.contentType(), row.confirmedAt(), null));
        return true;
    }

    /** 软删除。 */
    @Override
    public boolean markDeleted(String id, LocalDateTime deletedAt) {
        StoredFile row = rows.get(id);
        if (row == null || row.status() == StoredFileStatus.DELETED) {
            return false;
        }
        rows.put(
                id,
                copy(
                        row,
                        row.bizId(),
                        StoredFileStatus.DELETED,
                        row.sizeBytes(),
                        row.contentType(),
                        row.confirmedAt(),
                        deletedAt));
        return true;
    }

    /** 按条件分页。 */
    @Override
    public StoredFilePage page(StoredFileQuery query, long current, long size) {
        List<StoredFile> matched = rows.values().stream()
                .filter(row -> row.status() == StoredFileStatus.ACTIVE)
                .filter(row -> blank(query.keyword()) || row.originalName().contains(query.keyword()))
                .filter(row -> blank(query.bizType()) || row.bizType().equals(query.bizType()))
                .filter(row -> blank(query.bizId()) || query.bizId().equals(row.bizId()))
                .filter(row -> blank(query.uploaderId()) || row.uploaderId().equals(query.uploaderId()))
                .sorted(Comparator.comparing(StoredFile::createdAt).reversed())
                .toList();
        int from = (int) Math.min(matched.size(), (Math.max(1, current) - 1) * size);
        int to = (int) Math.min(matched.size(), from + size);
        return new StoredFilePage(Math.max(1, current), size, matched.size(), matched.subList(from, to));
    }

    /** 查询过期记录。 */
    @Override
    public List<StoredFile> findExpired(StoredFileStatus status, LocalDateTime before, int limit) {
        return rows.values().stream()
                .filter(row -> row.status() == status)
                .filter(row ->
                        (status == StoredFileStatus.DELETED ? row.deletedAt() : row.createdAt()).isBefore(before))
                .limit(limit)
                .toList();
    }

    /** 物理删除。 */
    @Override
    public void purge(String id) {
        rows.remove(id);
    }

    /**
     * 当前保存的记录数，含软删除与待确认。
     *
     * @return 记录数
     */
    public int count() {
        return rows.size();
    }

    /**
     * 判断字符串是否为空白。
     *
     * @param value 待判断的值
     * @return 空或全空白时为 {@code true}
     */
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 复制记录并替换会变化的字段。
     *
     * @param row 原记录
     * @param bizId 新的业务记录 ID
     * @param status 新状态
     * @param size 新大小
     * @param type 新类型
     * @param confirmedAt 新确认时间
     * @param deletedAt 新删除时间，不变时传空并沿用原值
     * @return 新记录
     */
    private static StoredFile copy(
            StoredFile row,
            String bizId,
            StoredFileStatus status,
            long size,
            String type,
            LocalDateTime confirmedAt,
            LocalDateTime deletedAt) {
        return new StoredFile(
                row.id(),
                row.storageRef(),
                row.objectKey(),
                row.originalName(),
                type,
                size,
                row.sha256(),
                row.bizType(),
                bizId,
                status,
                row.uploaderId(),
                row.createdAt(),
                confirmedAt,
                deletedAt != null ? deletedAt : row.deletedAt());
    }
}
