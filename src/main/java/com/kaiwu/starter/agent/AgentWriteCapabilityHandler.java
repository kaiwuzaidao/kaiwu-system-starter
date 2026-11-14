package com.kaiwu.starter.agent;

import com.kaiwu.starter.KaiwuContext;
import com.kaiwu.starter.agent.protocol.CapabilityManifest;
import java.util.Map;

/** 显式接入的领域写操作。预览必须只读；执行须检查资源归属和预览时的版本。 */
public interface AgentWriteCapabilityHandler {
    CapabilityManifest manifest();

    AgentWritePreview preview(Map<String, Object> parameters, KaiwuContext actor);

    /** 在执行账本与业务服务共用的数据库事务中调用，不得另开事务或产生外部副作用。 */
    String execute(Map<String, Object> parameters, String preconditionsJson, KaiwuContext actor);
}
