package com.kaiwu.datascope;

/**
 * 一个受控资源：业务表及其两个归属列（{@code kaiwu/data-scopes.yml} 的 {@code resources[]}）。
 *
 * @param code            资源码，形如 {@code crm:customer}
 * @param table           表名，小写
 * @param ownerUserColumn 归属人列，小写
 * @param ownerDeptColumn 归属部门列，小写
 */
public record DataScopeResource(String code, String table, String ownerUserColumn, String ownerDeptColumn) {}
