# 子项目写任务事务账本与固定入口（D1 / D2b）

SDK 提供业务项目可复用的写 Handler 类型、预览值类型、MySQL 账本参考实现，以及独立的固定写入口。写入口由 `kaiwu.starter.agent.write.enabled=true` 单独启用，默认关闭；启用时还必须配置 `kaiwu.starter.agent.write.project-id`，提供固定 `AgentWriteCapabilityHandler`、`AgentWriteLedger`、共享 `AgentReplayStore`、Gateway Context 公钥和权限解析器。缺一则应用启动失败。现有只读 `/invoke` 和普通业务 API 均不接受写 Context。System/Gateway 的确认与委托代码已实现，但部署写开关默认关闭；首个业务试点仍需数据库迁移和真实端到端验收。完整协议见 System 的 `docs/AGENT_REMOTE_ACTIONS.md`。

固定端点是 `POST /api/internal/agent/capabilities/{code}/preview|execute|result`。每次调用都验证 `kaiwu-agent-write-context+jwt` 的唯一用途、能力版本/manifest 摘要、原始 JSON 参数与前置条件摘要、预览摘要、actionId、过期时间、项目和一次性 jti。预览与执行复核当前 Handler 权限；RESULT 只读原动作绑定的事务账本，旧版本 Handler 下线后仍可核实，但必须由 System 对原用户会话和当前项目权限签发 RESULT Context。入口不应配置为外部 Gateway 路由。SDK 拒绝普通 Bearer Authorization，且不会自行接受浏览器确认消息作为执行授权。

## 子项目接入条件

每个子项目按自己的发布节奏接入，不能依靠平台扫描现有 API：

1. 选定一项可在同一数据库事务完成的领域操作，为它声明项目命名空间内的版本化 manifest：`readOnly=false`、`idempotent=true`、`confirmation=REQUIRED|STRONG`，权限码对应目标项目的有效 BUTTON。
2. 实现固定 `AgentWriteCapabilityHandler`。预览只读且返回可核对的目标、变更和资源版本；执行再次校验当前用户的资源归属、状态和版本，返回受白名单约束的结果。查询结果只读账本，不重新执行业务操作。
3. 通过本项目 Flyway 增加账本及必要的资源版本列，给同一数据源配置 `AgentWriteLedger` 和跨实例共享重放存储。用一次性数据库验证并发同一 actionId 只提交一次、错绑定拒绝、领域或结果写入失败整体回滚、提交后响应丢失可查。
4. 部署后签发该项目独立注册凭据、配置显式 PROJECT Route，并由目标项目管理员逐版核对 manifest 摘要及 Handler 证据。审核只表明该版本准入；System、Gateway 和项目各自的写开关仍需在隔离端到端验收后开启。

首批仅接收同一业务数据库事务内的短操作。支付、邮件、外部 HTTP 和长任务须另行设计可靠投递与幂等协议，不能套用本账本保证。

业务数据库先执行本项目自己的 Flyway 迁移，表与业务表位于同一个 MySQL 数据库。以下是参考 SQL，接入时用业务项目的下一个迁移版本，不要在应用启动时自动建表：

```sql
CREATE TABLE kaiwu_agent_write_receipt (
    project_id VARCHAR(128) NOT NULL,
    action_id CHAR(36) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    capability_code VARCHAR(128) NOT NULL,
    capability_version VARCHAR(128) NOT NULL,
    expires_at_millis BIGINT NOT NULL,
    binding_hash CHAR(64) NOT NULL,
    result_json TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, action_id)
) ENGINE=InnoDB;
```

业务项目引入 `spring-jdbc` 与自己使用的 MySQL driver，并提供与领域 Service **同一个** `DataSource` 的 `DataSourceTransactionManager`。创建 `JdbcAgentWriteLedger` 时传入二者；构造器拒绝不同 DataSource。账本类不会自动装配，避免只升级 SDK 就出现写入口。

`AgentWriteCapabilityHandler.preview()` 只读，按当前用户检查资源归属并返回已脱敏的显示字段、受影响数量和资源版本等前置条件。`AgentWritePreview` 可用 `displayLabels` 为字段名提供安全纯文本中文标签，标签键必须对应显示字段；旧三参数构造器继续可用。`execute()` 在账本事务里重新验证归属、状态及资源版本。它不能使用 `REQUIRES_NEW`、独立连接或事务外的外部调用；否则账本提交不再证明领域变更与结果一起提交。

`AgentWriteAction` 由固定入口从已验签 Context 生成，绑定项目、用户、会话、能力版本、manifest 摘要、参数摘要、前置条件摘要、预览摘要和过期时间。账本以 `(project_id, action_id)` 唯一键去重，同时比较完整绑定摘要：同一动作重复到达时只返回已提交结果；同一 ID 携带不同绑定则拒绝。结果最大 16 KiB，必须是 JSON 对象。事务写入账本占位、调用领域 Service、持久化结果；任一步失败都回滚。

调用方遇到超时或连接中断只能用 `result(action)` 核实。空结果可能意味着请求还在途、事务回滚或根本未到达，不能据此自动重新执行；结果查询也不再次调用领域 Handler。已提交的账本记录不自动删除，后续需要按业务审计保留期制定保留去重键的清理策略。

当前 MySQL 测试覆盖 16 路并发、绑定冲突、领域与结果错误回滚、提交后结果查询、过期拒绝及不同 DataSource 拒绝。写入口测试覆盖用途隔离、签名字段替换、重放、权限、输出字段投影和默认关闭。`AgentWriteLedger` 是业务方可替换的 SPI，但任何替换实现都必须提供同等的跨实例唯一键和事务证据。
