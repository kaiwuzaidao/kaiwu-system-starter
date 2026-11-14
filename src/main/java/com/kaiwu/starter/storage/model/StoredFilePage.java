package com.kaiwu.starter.storage.model;

import java.util.List;

/**
 * 文件分页结果，字段形状与生成项目的 {@code PageResult} 一致，前端可直接复用表格。
 *
 * @param current 当前页，从 1 开始
 * @param size 每页条数
 * @param total 符合条件的总条数
 * @param records 当前页记录
 */
public record StoredFilePage(long current, long size, long total, List<StoredFile> records) {
    /**
     * 复制记录列表，避免调用方修改返回值。
     *
     * @param current 当前页
     * @param size 每页条数
     * @param total 总条数
     * @param records 当前页记录
     */
    public StoredFilePage {
        records = List.copyOf(records);
    }
}
