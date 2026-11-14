package com.kaiwu.starter.storage.repository;

import com.kaiwu.starter.storage.model.StoredFile;
import com.kaiwu.starter.storage.model.StoredFilePage;
import com.kaiwu.starter.storage.model.StoredFileQuery;
import com.kaiwu.starter.storage.model.StoredFileStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 基于 {@link JdbcTemplate} 的文件记录实现，表结构见 {@code docs/storage-file.sql}。
 * 所有查询都带参数绑定；{@code LIKE} 的通配符会被转义，关键字不能被当成模式使用。
 */
public class JdbcStoredFileRepository implements StoredFileRepository {

    private static final String COLUMNS =
            "id, storage_ref, object_key, original_name, content_type, size_bytes, sha256, biz_type, biz_id,"
                    + " status, uploader_id, created_at, confirmed_at, deleted_at";

    private final JdbcTemplate jdbc;
    private final boolean retentionEnabled;

    private final RowMapper<StoredFile> mapper = JdbcStoredFileRepository::map;

    /**
     * 创建仓库。
     *
     * @param jdbc 业务项目自己的数据源对应的模板
     */
    public JdbcStoredFileRepository(JdbcTemplate jdbc) {
        this(jdbc, false);
    }

    /** 显式启用保留协议；部署方必须先应用文件引用迁移。 */
    public JdbcStoredFileRepository(JdbcTemplate jdbc, boolean retentionEnabled) {
        this.jdbc = jdbc;
        this.retentionEnabled = retentionEnabled;
    }

    /** 在文件行锁内建立持久引用；删除先完成时拒绝引用，不跨网络持锁。 */
    @Override
    public boolean retain(String id, String ownerService, String reference) {
        if (!retentionEnabled) {
            throw new UnsupportedOperationException("file retention unavailable");
        }
        if (ownerService == null
                || !ownerService.matches("[A-Za-z0-9_-]{1,64}")
                || reference == null
                || !reference.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("invalid file reference");
        }
        return transaction().execute(status -> {
            var states =
                    jdbc.query("SELECT status FROM kaiwu_file WHERE id=? FOR UPDATE", (rs, row) -> rs.getString(1), id);
            if (states.isEmpty() || !"ACTIVE".equals(states.getFirst())) {
                return false;
            }
            jdbc.update(
                    "INSERT IGNORE INTO kaiwu_file_reference(file_id,owner_service,reference_id) VALUES (?,?,?)",
                    id,
                    ownerService,
                    reference);
            return exactReferenceExists(id, ownerService, reference);
        });
    }

    /** 锁定文件后精确删除单条引用；调用方必须在同事务中确认业务引用已失效。 */
    @Override
    public boolean release(String id, String ownerService, String reference) {
        if (!retentionEnabled) {
            throw new UnsupportedOperationException("file retention unavailable");
        }
        return transaction().execute(status -> {
            var files =
                    jdbc.query("SELECT status FROM kaiwu_file WHERE id=? FOR UPDATE", (rs, row) -> rs.getString(1), id);
            if (files.isEmpty() || !"ACTIVE".equals(files.getFirst())) {
                return false;
            }
            return jdbc.update(
                            "DELETE FROM kaiwu_file_reference WHERE file_id=? AND BINARY owner_service=BINARY ? AND BINARY reference_id=BINARY ?",
                            id,
                            ownerService,
                            reference)
                    == 1;
        });
    }

    /** 清理前CAS转为PURGING，阻止迟到确认、绑定与新引用；允许失败清理重试。 */
    @Override
    public boolean claimPurge(StoredFile file) {
        if (!retentionEnabled) {
            return true;
        }
        return transaction().execute(status -> {
            var states = jdbc.query(
                    "SELECT status FROM kaiwu_file WHERE id=? FOR UPDATE", (rs, row) -> rs.getString(1), file.id());
            if (states.isEmpty()
                    || (!states.getFirst().equals(file.status().name())
                            && !states.getFirst().equals("PURGING"))
                    || (!states.getFirst().equals("PENDING")
                            && !states.getFirst().equals("DELETED")
                            && !states.getFirst().equals("PURGING"))) {
                return false;
            }
            if (hasReferences(file.id())) {
                return false;
            }
            return jdbc.update("UPDATE kaiwu_file SET status='PURGING' WHERE id=?", file.id()) == 1;
        });
    }

    /** 与retain共享文件行锁，引用存在时连软删也拒绝。 */
    private boolean protectedDelete(String id, LocalDateTime deletedAt) {
        return transaction().execute(status -> {
            var states =
                    jdbc.query("SELECT status FROM kaiwu_file WHERE id=? FOR UPDATE", (rs, row) -> rs.getString(1), id);
            if (states.isEmpty()
                    || (!states.getFirst().equals("ACTIVE")
                            && !states.getFirst().equals("PENDING"))
                    || hasReferences(id)) {
                return false;
            }
            return jdbc.update(
                            "UPDATE kaiwu_file SET status='DELETED',deleted_at=? WHERE id=?",
                            Timestamp.valueOf(deletedAt),
                            id)
                    == 1;
        });
    }

    /** 文件锁内使用当前读核对引用，调用方已有RR快照也不能隐藏并发提交的保留。 */
    private boolean hasReferences(String id) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM kaiwu_file_reference WHERE file_id=? FOR UPDATE", Long.class, id)
                > 0;
    }

    /** 不将旧表不区分大小写的唯一键冲突误判为精确引用已建立；必须在文件行锁内调用。 */
    private boolean exactReferenceExists(String id, String ownerService, String reference) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM kaiwu_file_reference WHERE file_id=? AND BINARY owner_service=BINARY ? AND BINARY reference_id=BINARY ? FOR UPDATE",
                        Long.class,
                        id,
                        ownerService,
                        reference)
                == 1;
    }

    /** 使用同一DataSource，加入调用方已有事务，不持有事务等待对象存储。 */
    private TransactionTemplate transaction() {
        return new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }

    /** 仅允许预留身份的一次性CAS；失败或回执未知后不能再次put相同key。 */
    @Override
    public boolean claimEvidenceUpload(String id) {
        if (!retentionEnabled) {
            throw new UnsupportedOperationException("file retention unavailable");
        }
        return jdbc.update("UPDATE kaiwu_file SET status='UPLOADING' WHERE id=? AND status='RESERVED'", id) == 1;
    }

    /** 文件行锁内激活并保留，读取到成功可幂等确认；不包含对象网络调用。 */
    @Override
    public boolean completeEvidenceUpload(String id, LocalDateTime now) {
        if (!retentionEnabled) {
            throw new UnsupportedOperationException("file retention unavailable");
        }
        return transaction().execute(status -> {
            var files = jdbc.query("SELECT " + COLUMNS + " FROM kaiwu_file WHERE id=? FOR UPDATE", mapper, id);
            if (files.isEmpty()) {
                return false;
            }
            var file = files.getFirst();
            if (file.status() != StoredFileStatus.UPLOADING && file.status() != StoredFileStatus.ACTIVE) {
                return false;
            }
            if (!file.uploaderId().startsWith("system:") || file.bizId() == null || file.sha256() == null) {
                return false;
            }
            jdbc.update(
                    "INSERT IGNORE INTO kaiwu_file_reference(file_id,owner_service,reference_id) VALUES (?,?,?)",
                    id,
                    file.uploaderId().substring(7),
                    file.bizId());
            if (!exactReferenceExists(id, file.uploaderId().substring(7), file.bizId())) {
                return false;
            }
            jdbc.update("UPDATE kaiwu_file SET status='ACTIVE',confirmed_at=? WHERE id=?", Timestamp.valueOf(now), id);
            return true;
        });
    }

    /** 新增一条文件记录。 */
    @Override
    public void insert(StoredFile file) {
        jdbc.update(
                "INSERT INTO kaiwu_file (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                file.id(),
                file.storageRef(),
                file.objectKey(),
                file.originalName(),
                file.contentType(),
                file.sizeBytes(),
                file.sha256(),
                file.bizType(),
                file.bizId(),
                file.status().name(),
                file.uploaderId(),
                Timestamp.valueOf(file.createdAt()),
                file.confirmedAt() == null ? null : Timestamp.valueOf(file.confirmedAt()),
                file.deletedAt() == null ? null : Timestamp.valueOf(file.deletedAt()));
    }

    /** 按 ID 查询记录，任何状态都返回。 */
    @Override
    public Optional<StoredFile> find(String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM kaiwu_file WHERE id = ?", mapper, id).stream()
                .findFirst();
    }

    /** 把待确认记录置为生效，仅当前状态是待确认时才更新。 */
    @Override
    public boolean activate(String id, long sizeBytes, String contentType, LocalDateTime confirmedAt) {
        return jdbc.update(
                        "UPDATE kaiwu_file SET status = 'ACTIVE', size_bytes = ?, content_type = ?, confirmed_at = ?"
                                + " WHERE id = ? AND status = 'PENDING'",
                        sizeBytes,
                        contentType,
                        Timestamp.valueOf(confirmedAt),
                        id)
                > 0;
    }

    /** 把生效中的文件挂到业务记录上。 */
    @Override
    public boolean bind(String id, String bizId) {
        return jdbc.update("UPDATE kaiwu_file SET biz_id = ? WHERE id = ? AND status = 'ACTIVE'", bizId, id) > 0;
    }

    /** 软删除未删除的记录。 */
    @Override
    public boolean markDeleted(String id, LocalDateTime deletedAt) {
        if (retentionEnabled) {
            return protectedDelete(id, deletedAt);
        }
        return jdbc.update(
                        "UPDATE kaiwu_file SET status = 'DELETED', deleted_at = ? WHERE id = ? AND status <> 'DELETED'",
                        Timestamp.valueOf(deletedAt),
                        id)
                > 0;
    }

    /** 分页查询生效中的文件，按创建时间倒序。 */
    @Override
    public StoredFilePage page(StoredFileQuery query, long current, long size) {
        long safeCurrent = Math.max(1, current);
        long safeSize = Math.min(Math.max(1, size), 100);
        StringBuilder where = new StringBuilder(" WHERE status = 'ACTIVE'");
        List<Object> args = new ArrayList<>();
        if (hasText(query.keyword())) {
            where.append(" AND original_name LIKE ? ESCAPE '\\\\'");
            args.add("%" + escapeLike(query.keyword().trim()) + "%");
        }
        if (hasText(query.bizType())) {
            where.append(" AND biz_type = ?");
            args.add(query.bizType().trim());
        }
        if (hasText(query.bizId())) {
            where.append(" AND biz_id = ?");
            args.add(query.bizId().trim());
        }
        if (hasText(query.uploaderId())) {
            where.append(" AND uploader_id = ?");
            args.add(query.uploaderId().trim());
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM kaiwu_file" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((safeCurrent - 1) * safeSize);
        List<StoredFile> records = jdbc.query(
                "SELECT " + COLUMNS + " FROM kaiwu_file" + where
                        + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                mapper,
                pageArgs.toArray());
        return new StoredFilePage(safeCurrent, safeSize, total == null ? 0 : total, records);
    }

    /** 找出早于截止时间、仍处于指定状态的记录。 */
    @Override
    public List<StoredFile> findExpired(StoredFileStatus status, LocalDateTime before, int limit) {
        String column = status == StoredFileStatus.DELETED ? "deleted_at" : "created_at";
        if (retentionEnabled) {
            return jdbc.query(
                    "SELECT " + COLUMNS + " FROM kaiwu_file WHERE (status=? AND " + column
                            + " < ?) OR (status='PURGING' AND ?='DELETED') ORDER BY created_at,id LIMIT ?",
                    mapper,
                    status.name(),
                    Timestamp.valueOf(before),
                    status.name(),
                    Math.max(1, limit));
        }
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM kaiwu_file WHERE status = ? AND " + column + " < ? ORDER BY " + column
                        + " LIMIT ?",
                mapper,
                status.name(),
                Timestamp.valueOf(before),
                Math.max(1, limit));
    }

    /** 物理删除一条记录。 */
    @Override
    public void purge(String id) {
        if (retentionEnabled) {
            jdbc.update(
                    "DELETE FROM kaiwu_file WHERE id=? AND status='PURGING' AND NOT EXISTS (SELECT 1 FROM kaiwu_file_reference WHERE file_id=?)",
                    id,
                    id);
        } else {
            jdbc.update("DELETE FROM kaiwu_file WHERE id = ?", id);
        }
    }

    /**
     * 把结果集一行转成记录。
     *
     * @param rs 结果集，定位在当前行
     * @param row 行号，未使用
     * @return 文件记录
     * @throws SQLException 读取列失败时抛出
     */
    private static StoredFile map(ResultSet rs, int row) throws SQLException {
        return new StoredFile(
                rs.getString("id"),
                rs.getString("storage_ref"),
                rs.getString("object_key"),
                rs.getString("original_name"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getString("biz_type"),
                rs.getString("biz_id"),
                StoredFileStatus.valueOf(rs.getString("status")),
                rs.getString("uploader_id"),
                time(rs.getTimestamp("created_at")),
                time(rs.getTimestamp("confirmed_at")),
                time(rs.getTimestamp("deleted_at")));
    }

    /**
     * 空安全地把时间戳转成本地时间。
     *
     * @param value 数据库时间戳，可为空
     * @return 本地时间，空值返回空
     */
    private static LocalDateTime time(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    /**
     * 判断字符串是否有有效内容。
     *
     * @param value 待判断的值
     * @return 非空且去空白后仍有内容时为 {@code true}
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 转义 LIKE 通配符，让关键字只按字面匹配。
     *
     * @param value 原始关键字
     * @return 转义后的关键字
     */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
