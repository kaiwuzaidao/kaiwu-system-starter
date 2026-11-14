# 类型化写操作适配

`TypedAgentWrite<I,R>` 实现现有 `AgentWriteCapabilityHandler`。输入字段由 `AgentInputSchema<I>` 一次生成参数约束、中文表单和 DTO 绑定；执行返回类型化结果，由显式 `AgentOutput<R>` getter生成安全 JSON。业务不用手写执行参数 Map 或结果 Map；不会自动开放 Controller。

接入步骤：

1. 用 AgentInput 定义有界字段；用 AgentInputSchema 的工厂组装业务 DTO。身份和权限不得作为模型输入。
   多选 ID 可用 `AgentInput.stringList(name, label, required, minItems, maxItems, maxItemLength)`；它只校验 JSON 数组形状、数量、非空、长度和去重，每个 ID 是否属于目标项目仍由业务 Service 在预览与执行时验证。
2. 注册 TypedAgentWrite Bean，显式提供能力码、中文用途、已有权限、数据范围、版本及 REQUIRED/STRONG 确认方式。
3. 预览函数只读，返回原 AgentWritePreview：中文展示字段须已脱敏，preconditionsJson 由业务冻结资源版本等条件，不从模型取得。预览 Map 和前置条件仍为显式领域契约，本批不自动推导。
4. mutation 接收 DTO、冻结的 preconditionsJson 和已验证 actor。复用受保护业务 Service，在原事务内再次检查资源归属/状态/版本。返回业务结果；未声明的 getter 绝不序列化。
5. 继续走既有 Starter 写运行时。不得直接从 Controller 调用 adapter，不得另开事务、异步线程或在 mutation 中发送外部请求。外部副作用需要独立可靠投递方案。

构造形态（以下为业务绑定片段）：

```java
var name = AgentInput.text("name", "名称", true, 30, false);
var schema = new AgentInputSchema<>("修改名称", List.of(name),
    args -> new RenameInput(args.get(name)));
var handler = new TypedAgentWrite<RenameInput, RenameResult>(
    new TypedAgentWrite.Identity("demo", "demo.config.rename", "1",
        "修改配置名称", "config:config:update", DataScope.MEMBER_PROJECTS),
    schema, List.of(AgentOutput.text("id", "配置编号", RenameResult::id)),
    ConfirmationMode.REQUIRED, 3000,
    service::previewAuthorizedRename, service::executeAuthorizedRename);
```

业务负责上例 DTO/Service；通过 Bean 工厂返回 handler。预览与执行都必须执行领域授权。声明只代表接入，不代表拥有权限或审批通过。

适配器不新增令牌、端点、权限策略、确认票据或幂等缓存；签名用途、参数摘要、预览摘要、权限、单次 Context、事务账本、结果查询仍由既有运行时处理。`idempotent=true` 表示遵守既有账本契约，不能把 adapter 直接调用当成幂等执行。

输出仅声明字段白名单；没有新增写结果 outputView 协议。输出标签用于声明校验，当前写结果 UI 仍沿用原通用结果机制；不要把此适配器当作已实现编辑回填或完整 CRUD UI。敏感字段在业务层脱敏，首批不生成 redact 规则。可清空字段、金额输入、跨资源批量事务、文件、长任务仍待后续协议。

迁移旧能力先比较完整 manifest 摘要。新增表单会改变摘要，须升版本并审核；只有摘要完全一致才保留原版本。本批仅发布 SDK，不改变业务能力批准状态，不执行共享环境写操作。

验证包含原始/类型化两个版本的真实签名写运行时测试：缺权限、错误项目、未确认、替换摘要、用途串用、重放、Context 清理、旧版本结果查询。现有 MySQL 事务账本测试仍完整执行。
