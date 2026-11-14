package com.kaiwu.datascope;

import java.util.Set;

/**
 * 项目成员事实的查询入口，仅供 {@link DataScopes#reassign} 校验新归属。
 *
 * <p>Starter 不持有成员数据，也不在普通业务请求里回调 System（ADR 0003）。默认实现
 * {@link SystemMemberDirectory} 通过 System 的内部成员查询完成，只在归属转移这类低频管理动作里调用；
 * 业务侧也可以自己实现。实现必须在依赖不可用时抛出异常，而不是返回「不是成员」——那会把故障解释成确定的结论。</p>
 */
public interface MemberDirectory {

    /**
     * 一位用户在项目内的归属事实。
     *
     * @param active        是不是有效成员
     * @param departmentIds 所属部门；不是有效成员时为空
     */
    record Placement(boolean active, Set<Long> departmentIds) {

        /** 复制部门集合，保证记录不可变。 */
        public Placement {
            departmentIds = Set.copyOf(departmentIds);
        }
    }

    /**
     * @param userId 全局用户 ID
     * @return 该用户当前的成员资格与所属部门
     */
    Placement placement(String userId);
}
