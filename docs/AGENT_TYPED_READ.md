# 类型化只读接入（首批）

`TypedAgentRead<I, R>` 把显式字段定义生成现有 manifest、输入表单、输出投影和结果卡片。业务项目不必重复维护 ParamSpec、InputFormSpec、出站白名单及逐行 Map 转换。它实现原有 AgentReadCapabilityHandler，不新增执行入口，不自动发现 Controller，不改变权限或审核规则。

## 业务声明示例

```java
var page = AgentInput.integer("page", "页码", false, 1, 1000, 1);
var size = AgentInput.integer("pageSize", "每页条数", false, 1, 20, 10);
record Query(Integer page, Integer size) {}

@Bean
AgentReadCapabilityHandler orderList(OrderService service) {
    return new TypedAgentRead<Query, OrderView>(
        new TypedAgentRead.Identity("orders", "orders.record.list", "1",
            "查询有权查看的订单", "orders:record:list", DataScope.SELF),
        "查询订单", List.of(page, size),
        args -> new Query(args.get(page), args.get(size)),
        "订单列表", List.of(
            AgentOutput.text("id", "订单编号", OrderView::id),
            AgentOutput.number("amount", "金额", OrderView::amount)),
        new OutputViewSpec.Pagination("page", "pageSize"), null, 3000,
        (input, actor) -> service.listAuthorized(actor, input.page(), input.size()));
}
```

示例中的字段变量应置于配置类的适当作用域；Query/OrderView/Service 由业务仓维护。也可以让现有组件继承 TypedAgentRead，在构造器传入声明和服务绑定；manifest/query 固定不可覆盖。

字段定义只维护一次，DTO 工厂负责组装现有类型化请求；不会通过反射向 DTO 任意写字段。绑定函数拿到运行时验签后的 actor，业务 Service 必须执行原资源归属、状态等检查。不得从普通 Controller 直接调用 Handler 绕开 Starter 执行入口。

## 支持范围

- 输入：有界字符串、有界整数/默认值、布尔、固定枚举+受管字典、本地日期时间。
- 输出：显式字符串、数字、布尔、现有0/1启用值、字典、本地日期时间；字段 getter 未声明的实体属性绝不序列化。长ID使用text及String，不转成JavaScript数值。
- 日期在 DTO 内可用 LocalDateTime，传输仍是无时区的 ISO_LOCAL_DATE_TIME 字符串。精确金额沿用业务数值输出；输入金额、集合、关联选择、写任务及上传/任务协议不在首批范围。
- 兼容现有分页/详情声明；输出最多100行，超限报错不截断。空值沿用旧Handler语义省略。
- 脱敏在业务Service或显式getter中完成；首批不生成redact规则，不要把带敏感原值的旧manifest直接迁移成无脱敏版本。

## 迁移与验收

1. 固定 CI 正式发布后的SDK版本，不把本机安装当成可发布制品。
2. 用原Handler测试验证权限拒绝、参数、SQL筛选及输出一致性。
3. 比较 AgentExecutionCodec.manifestHash；只有完整manifest完全一致时才保留版本。字段/权限/标签/顺序变化须递增版本并重新审核，不为省审批隐藏变化。
4. 服务注册仍只用Handler单份manifest；不要额外声明同码Bean。
5. 新工具只减少样板代码，不证明业务已上线、已批准或真实聊天验收完成。
