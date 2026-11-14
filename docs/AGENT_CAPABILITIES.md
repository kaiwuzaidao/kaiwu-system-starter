# 接入子项目聊天查询

Starter 提供固定的 `AgentReadCapabilityHandler` 扩展点。业务项目实现并审核新 Handler 后，无需修改 System 编排器和聊天前端。能力不能自动从 Controller 扫描、不能把整个 OpenAPI 暴露给模型；每项能力应对应一个可描述、可校验权限的领域任务。

## 接入步骤

1. 使用 `com.kaiwuzaidao:kaiwu-system-starter:0.2.0`。保留原有 Gateway Context 验签、公钥、audience 和 project-code 配置。
2. 在项目菜单中登记对应 BUTTON 权限，并通过现有角色管理分配给需要使用的成员。平台管理员权限不能代替项目权限。
3. 添加只读 Handler Bean，返回同一份不可变 manifest；实现复用原业务 Service，并传入当前用户以执行数据归属检查。
4. 配置独立 Agent 注册凭据，开启注册和执行。原有配置键不变；新增执行开关与项目 ID：

```yaml
kaiwu:
  starter:
    project-code: orders
    audience: kaiwu-orders-service
    agent:
      enabled: true
      system-base-url: ${KAIWU_SYSTEM_BASE_URL}
      credential: ${KAIWU_AGENT_REGISTRY_CREDENTIAL}
      execution:
        enabled: true
        project-id: ${KAIWU_PROJECT_ID}
```

5. 业务服务提供共享的 `StringRedisTemplate`，SDK 自动装配 `RedisAgentReplayStore`。所有实例必须共用同一存储；Redis 不可用直接拒绝。没有 Redis 时可显式实现 `AgentReplayStore`，必须提供跨实例原子 claim + 过期保留，不能用 JVM Map 代替生产存储。
6. 运维配置该项目的显式 PROJECT Route（projectCode/audience/URI 三者绑定），并开启 System/Gateway 的远程只读开关。平台管理页审核该版本后，已授权成员可直接问“查询我的订单”。未审核、断开注册心跳超过 180 秒、版本变化或撤销后不再可用。

## 数据范围声明

`CapabilityManifest` 必须显式声明 `DataScope`：

| 值 | 含义 |
|---|---|
| `SELF` | 仅调用者自己的数据 |
| `MEMBER_PROJECTS` | 仅调用者是有效成员的项目 |
| `ALL` | 全平台可见，需要单独论证 |
| `ROLE_SCOPED` | 项目内，按调用者角色在受控资源上的数据范围（ADR 0044） |

读写受控资源（`kaiwu/data-scopes.yml` 里声明的资源）的能力应声明 `ROLE_SCOPED`；System 注册时会拒绝
对受控资源声明 `ALL` 的能力。真正的行级过滤由 `kaiwu-data-scope` 模块在 SQL 层完成，Agent Context 同样携带 `ds`。

## Handler 示例

下面是接入模式，`orderService` 为业务项目自己的领域服务；其 `listForUser` 必须按用户和资源状态执行最终授权。

```java
@Bean
AgentReadCapabilityHandler orderSearch(OrderService orderService) {
    var manifest = new CapabilityManifest(
        "orders", "orders.record.search", "1", "查询我有权查看的订单",
        "orders:record:list", DataScope.SELF,
        Map.of("keyword", ParamSpec.optionalString(100), "pageSize", ParamSpec.pageSize(50, 20)),
        Set.of("id", "orderNo", "status"), Map.of(), true, true, ConfirmationMode.NONE, 5000);
    return new AgentReadCapabilityHandler() {
        public CapabilityManifest manifest() { return manifest; }
        public List<Map<String, Object>> query(Map<String, Object> params, KaiwuContext actor) {
            return orderService.listForUser(actor.userId(), (String) params.get("keyword"),
                    ((Number) params.get("pageSize")).intValue()).stream()
                .map(order -> Map.<String, Object>of("id", order.id().toString(),
                        "orderNo", order.orderNo(), "status", order.status()))
                .toList();
        }
    };
}
```

不要再把同一 Handler 的 manifest 重复声明成独立 Bean。SDK 使用启动时冻结的 Handler 清单自动注册，重复能力码使启动失败。更改字段、权限、参数或描述必须递增 manifest 版本并重新审核。

## 运行边界

- 仅接受 `POST /api/internal/agent/capabilities/{code}/invoke`；调用方使用 Gateway 签发的 `kaiwu-agent-action-context+jwt`。不配置外部路由，不接受用户 JWT、注册凭据或普通 Context。
- Context 绑定项目、唯一 audience、能力/版本/清单摘要、原始 `paramsJson` SHA-256、correlationId 和一次性 jti。验证器与本地冻结清单逐项比较，再检查 manifest 权限；普通 `@RequirePermission` 路径不扩大令牌类型。
- 参数不允许用户/项目/角色/session 等身份字段，不允许任意对象；长度、整数、枚举和必填项严格校验。项目身份只从已签名 Context 取得。
- 每个服务最多 4 个并发 Handler、16 个待执行任务。超时会发出中断并停止等待，Java 无法强制终止忽略中断的业务代码，故查询本身还应配置数据库/HTTP 超时。不会自动重试。
- 最大 100 行、256 KiB 响应；只输出声明字段。System 再对浏览器和模型执行白名单与脱敏。大结果应由业务查询本身分页。
- Handler 线程在成功、异常和超时后均清理身份上下文。jti 消费后不会因失败释放；另一次聊天请求必须重新授权签发 Context。
- 本页只介绍只读 Handler。写操作必须使用独立的 `AgentWriteCapabilityHandler`、同事务账本和固定预览/执行/结果入口；已注册或已审核清单都不代表某项目已通过端到端验收。

写任务的接入步骤见[写任务说明](AGENT_WRITE_LEDGER.md)。写入口默认关闭，不能把写操作伪装成只读查询。


## 2026-09-27 跨项目声明式表单

新增可选 inputForm 契约，STRING/INT/BOOL 通用渲染、严格结构化输入与有序审批摘要。旧清单摘要不变，未自动开放任何业务能力。详见 [接入方法](AGENT_FORM_PRESENTATION.md)。

## 类型化查询绑定

已有查询可以使用 [TypedAgentRead](AGENT_TYPED_READ.md) 统一声明参数、表单和结果字段，
复用同一个业务服务，无需逐项手写参数/结果 Map。原 Handler SPI 保持兼容，
权限和资源归属规则继续由既有运行时及业务服务校验。

类型化写接入见 [AGENT_TYPED_WRITE.md](AGENT_TYPED_WRITE.md)。复用既有确认与事务账本，不自动开放业务操作。
