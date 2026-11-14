package com.kaiwu.starter.auth.model;

/** 已验证的项目准入；应用仍须按操作及资源归属执行授权。 */
public enum ProjectAdmission {
    FULL,
    DRAIN,
    READ_ONLY,
    DENY
}
