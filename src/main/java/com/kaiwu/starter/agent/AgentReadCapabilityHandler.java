package com.kaiwu.starter.agent;

import com.kaiwu.starter.KaiwuContext;
import com.kaiwu.starter.agent.protocol.CapabilityManifest;
import java.util.List;
import java.util.Map;

/** 显式接入的只读领域查询。实现必须复用领域服务并校验数据归属，不能执行写操作。 */
public interface AgentReadCapabilityHandler {
    CapabilityManifest manifest();

    List<Map<String, Object>> query(Map<String, Object> parameters, KaiwuContext actor);
}
