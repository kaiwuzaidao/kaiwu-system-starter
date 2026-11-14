package com.kaiwu.starter.agent.protocol;

/**
 * 能力的数据范围，用于没有 {@code projectId} 参数的能力（ADR 0030 第 4 条）。
 *
 * <p>必须显式声明：默认成 {@code ALL} 会让一条忘记标注的能力直接越过项目边界。
 * 曾经还有 {@code ORG}（按组织范围），因为当时无法执行而删除；现在数据范围已能执行
 * （ADR 0044，{@code kaiwu-data-scope}），所以以 {@link #ROLE_SCOPED} 如实表达，
 * 而不是在读写受控资源的能力上声明 {@link #ALL}。</p>
 */
public enum DataScope {

    /** 全平台可见，需要单独论证。 */
    ALL,

    /** 仅调用者是有效成员的项目。 */
    MEMBER_PROJECTS,

    /** 仅调用者自己的数据。 */
    SELF,

    /** 项目内，按调用者所属角色在受控资源上的数据范围（ADR 0044）。 */
    ROLE_SCOPED
}
