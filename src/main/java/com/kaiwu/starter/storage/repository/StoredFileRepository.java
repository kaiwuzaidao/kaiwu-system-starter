package com.kaiwu.starter.storage.repository;

import com.kaiwu.starter.storage.model.StoredFile;
import com.kaiwu.starter.storage.model.StoredFilePage;
import com.kaiwu.starter.storage.model.StoredFileQuery;
import com.kaiwu.starter.storage.model.StoredFileStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 文件记录的持久化抽象；默认实现是基于 JDBC 的 {@link JdbcStoredFileRepository}，业务可自行替换。 */
public interface StoredFileRepository {

    /** 为支持审批保留的实现创建幂等引用；不支持时失败关闭。 */
    default boolean retain(String id, String ownerService, String reference) {
        throw new UnsupportedOperationException("file retention unavailable");
    }

    /** 可信调用方确认引用已失效后精确释放；未支持的实现失败关闭。 */
    default boolean release(String id, String ownerService, String reference) {
        throw new UnsupportedOperationException("file release unavailable");
    }

    /** 在对象删除前原子领取清理权；旧自定义实现保持原清理契约。 */
    default boolean claimPurge(StoredFile file) {
        return true;
    }

    /** 原子取得预留证据的一次性写权，不支持的实现失败关闭。 */
    default boolean claimEvidenceUpload(String id) {
        throw new UnsupportedOperationException("evidence unavailable");
    }
    /** 原子激活证据并建立保留引用，必须与预留摘要/来源对应。 */
    default boolean completeEvidenceUpload(String id, LocalDateTime now) {
        throw new UnsupportedOperationException("evidence unavailable");
    }

    /**
     * 新增一条文件记录。
     *
     * @param file 待保存的记录
     */
    void insert(StoredFile file);

    /**
     * 按 ID 查询，任何状态都返回。
     *
     * @param id 文件 ID
     * @return 记录，不存在时为空
     */
    Optional<StoredFile> find(String id);

    /**
     * 把待确认记录置为生效，并回填确认后读到的大小与类型。仅当当前状态是待确认时才更新。
     *
     * @param id 文件 ID
     * @param sizeBytes 复核后的字节数
     * @param contentType 复核后的 MIME 类型
     * @param confirmedAt 确认时间
     * @return 实际更新了记录时为 {@code true}
     */
    boolean activate(String id, long sizeBytes, String contentType, LocalDateTime confirmedAt);

    /**
     * 把生效中的文件挂到业务记录上。
     *
     * @param id 文件 ID
     * @param bizId 业务记录 ID
     * @return 记录存在且处于生效状态时为 {@code true}
     */
    boolean bind(String id, String bizId);

    /**
     * 软删除生效中或待确认的记录；已删除的记录不重复处理。
     *
     * @param id 文件 ID
     * @param deletedAt 删除时间
     * @return 实际更新了记录时为 {@code true}
     */
    boolean markDeleted(String id, LocalDateTime deletedAt);

    /**
     * 分页查询生效中的文件，按创建时间倒序。
     *
     * @param query 筛选条件
     * @param current 页码，从 1 开始
     * @param size 每页条数
     * @return 分页结果
     */
    StoredFilePage page(StoredFileQuery query, long current, long size);

    /**
     * 找出创建时间早于截止时间、仍处于指定状态的记录，用于清理。
     *
     * @param status 目标状态；待确认按创建时间判断，已删除按删除时间判断
     * @param before 截止时间
     * @param limit 最多返回条数
     * @return 记录列表
     */
    List<StoredFile> findExpired(StoredFileStatus status, LocalDateTime before, int limit);

    /**
     * 物理删除一条记录，仅用于清理任务在对象已删除之后。
     *
     * @param id 文件 ID
     */
    void purge(String id);
}
