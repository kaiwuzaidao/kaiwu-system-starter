package com.kaiwu.starter.auth.service;

import com.kaiwu.starter.auth.model.ProjectAdmission;
import java.util.Optional;

/** 应用提供的部署级登记查询，必须直读权威主库的已提交数据。 */
@FunctionalInterface
public interface TrustedProjectRegistry {

    /** 仅接收已验签的项目编码。不存在返回 empty，查询故障须抛异常。 */
    Optional<Registration> findByProjectCode(String projectCode) throws Exception;

    /** 登记绑定由应用负责维护唯一性，revision 为权威登记的版本。 */
    record Registration(String projectId, String audience, long revision, ProjectAdmission admission) {}
}
