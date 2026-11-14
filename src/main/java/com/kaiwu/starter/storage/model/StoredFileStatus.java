package com.kaiwu.starter.storage.model;

/** 文件记录的生命周期状态（ADR 0041）。 */
public enum StoredFileStatus {
    /** 已签发直传地址但尚未确认，超时未确认的记录由清理任务回收。 */
    PENDING,
    /** 已确认且可被业务引用、下载。 */
    ACTIVE,
    /** 已软删除：不再可见，保留期过后由清理任务删除对象与记录。 */
    DELETED,
    /** 后台证据身份已持久预留，尚未写入对象，不参与普通孤儿清理。 */
    RESERVED,
    /** 一次性写入已开始，结果未知时只能读取核对，不得重写。 */
    UPLOADING,
    /** 已领取清理权，禁止再次确认或引用；对象清理失败时可重试。 */
    PURGING
}
