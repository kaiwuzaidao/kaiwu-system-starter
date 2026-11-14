# Kaiwu System Starter

业务服务接入 SDK，只负责：

- 本地验证 Gateway 签发的 RS256、audience-bound Context。
- 建立并清理 `StarterContext`。
- 执行 `@RequirePermission("mod:res:act")`。
- 允许 System 通过 `PermissionResolver` 覆盖平台权限来源。
- 可选启用项目 scheduler：只同步凭据绑定项目的配置，抢占中央租约后在业务进程内执行。
- 可选向平台投递站内信（`NotificationClient`）与统一待办（`TodoClient`），见下文「站内信与统一待办」。

Starter 不依赖 `kaiwu-system-api`，不信任 `X-Project-Id` 或普通身份 Header。默认鉴权链路
不回调 System；只有显式启用 scheduler 或站内信客户端时才使用独立服务凭据访问内部接口。

## 项目 scheduler

业务任务只能注册固定 Handler：

```java
@Component
public class DailyReportTask implements ProjectScheduledTaskHandler {
    @Override
    public String taskType() {
        return "report.daily";
    }

    @Override
    public void execute(ProjectScheduledTaskContext context) {
        // context.payload() 是 System 校验过的 JSON 对象字符串
    }
}
```

```yaml
kaiwu:
  starter:
    scheduler:
      enabled: true
      system-base-url: http://kaiwu-system-service:8080
      project-id: ${KAIWU_PROJECT_ID}
      credential: ${KAIWU_SCHEDULER_CREDENTIAL}
      instance-id: ${HOSTNAME:local}
      # 该池同时承担 Cron 触发与 Handler 执行，池满时其它任务到点不会被触发；
      # 存在长任务或任务较多时，调大到并发任务数加余量
      pool-size: 4
      connect-timeout-seconds: 3
      read-timeout-seconds: 10
      lease-renew-interval-seconds: 60
      max-consecutive-renew-failures: 3
      # System 当前租约为 5 分钟；恢复重试需晚于租约到期
      claim-retry-delay-seconds: 360
      max-claim-retry-attempts: 2
```

Starter 启动即上报 Handler 并同步当前项目任务，配置变更自动重排。相同
`jobId + scheduledAt` 由 System 原子抢占；长任务自动续租，完成后回传状态和失败摘要。
System 不会下发 Java 类名、Shell 或任意 URL。执行语义为 at-least-once：Handler 应使用
`context.executionId()`，或 `context.jobId() + context.scheduledAt()` 建立业务幂等键。

抢占失败的实例会为同一个 `scheduledAt` 做有限次数的延迟恢复抢占，同时下一次 Cron
会先独立登记。续租被拒绝或连续失败达到阈值时，Starter 会标记
`context.isLeaseActive()` 为 `false` 并中断 Handler 线程。Java 无法强制撤销任意已经
发生的数据库或远程副作用，因此长任务还必须在批次边界、外部写入前调用
`context.assertLeaseActive()`；支持条件写入的下游应同时校验
`context.fencingToken()`。该协作式栅栏与业务幂等共同保证 at-least-once 场景下的安全性。

### 运行前提

- **线程池容量**：`pool-size` 决定同时可触发与执行的任务数。Handler 在 Cron 触发线程内
  同步执行，池被长任务占满时，其它任务到点不会被触发而是排队，表现为延迟而非报错。
  部署前按"并发任务数 + 余量"设置。
- **时钟同步**：每个实例本地按 Cron 推导触发时刻。推导结果是绝对时间点（例如今天 02:00），
  各实例算出的值相同，因此**去重不受时钟影响**；但实例时钟偏快多少，就会提前多少触发并
  抢到租约。生产环境需保证 NTP 同步。
- **凭据轮换**：明文只在轮换时返回一次，数据库仅存 BCrypt 哈希，不支持双凭据并行过渡。
  轮换意味着更新 `KAIWU_SCHEDULER_CREDENTIAL` 并重启实例。重启前旧凭据同步会失败，
  但 Starter 会保留最后一次有效配置继续执行，已配置的任务不会中断。

构建：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export MAVEN_SKIP_RC=1
mvn -s .mvn/settings.xml verify
```

## 站内信与统一待办

业务服务可以向平台统一收件箱投递**站内信**，或向平台工作台投递**待办**。两者的区别：

| | 站内信 `NotificationClient` | 待办 `TodoClient` |
|---|---|---|
| 性质 | 不可变事实，只有已读 / 未读 | 状态由**业务服务自己维护**，办完自动消失 |
| 典型场景 | 订单已超时、导出完成 | 待审批、待核对、待处理工单 |

两者都由 Spring 自动注入，**共用同一份配置**（System 地址与服务投递凭据）：

```yaml
kaiwu:
  starter:
    project-code: flow              # 本项目编码，作为消息 / 待办的来源项目
    notification:
      system-base-url: ${KAIWU_SYSTEM_SERVICE_URI:}
      delivery-token: ${KAIWU_NOTIFICATION_DELIVERY_TOKEN:}   # 只从环境变量注入，禁止写进仓库
```

缺地址或凭据时两个客户端都静默跳过，不影响启动与业务。

### 统一待办 `TodoClient`

```java
@Service
public class ApprovalService {

    private final TodoClient todoClient;

    public ApprovalService(TodoClient todoClient) {
        this.todoClient = todoClient;
    }

    /** 提交审批后：通知审批人。同一事项的多位处理人共用同一个业务键。 */
    public void submit(Approval approval, List<String> approverIds) {
        // ……保存审批单（业务事务）……
        for (String approver : approverIds) {
            todoClient.open(approver, "approval:" + approval.getNo(), "待审批：" + approval.getTitle());
        }
    }

    /** 或签：有人办完后，关闭这个事项下所有人的待办。 */
    public void approve(Approval approval) {
        todoClient.close("approval:" + approval.getNo());
    }

    /** 会签：每人办完只关闭自己的。 */
    public void approveAsSigner(Approval approval, String signerId) {
        todoClient.close("approval:" + approval.getNo(), signerId);
    }

    /** 事项取消：待办标为「已取消」而不是「办理完成」。 */
    public void withdraw(Approval approval) {
        todoClient.cancel("approval:" + approval.getNo());
    }
}
```

完整签名：

- `open(处理人, 业务键, 标题)`；`open(处理人, 业务键, 标题, 正文, 链接, 截止时间)`——新建或重新打开，同一个业务键重复调用是幂等的。
- `close(业务键)` / `close(业务键, 处理人)`——办理完成，关闭所有人 / 只关闭一人。
- `cancel(业务键)` / `cancel(业务键, 处理人)`——事项取消，同样两种范围。

约定：

- **只在明确的业务事件后显式调用**，不要挂在拦截器上或随每个请求触发。
- **失败只记日志，绝不抛异常**，不会阻断你的业务事务；**业务流程不得依赖它推进状态**，也不要用它的结果做判断。
- `业务键` 是业务事项的唯一键，由业务项目约定（如 `approval:2026-0001`），不超过 128 个字符，只能含字母、数字与 `: _ . / # -`。
- **打开**时处理人必须是本项目的有效成员，否则平台拒绝；**关闭**不要求。
- 链接可空，缺省时点击待办进入本项目后台首页；截止时间使用与 System 相同时区的本地时间，过期后工作台显示「已逾期」。
- 每个项目、每位处理人同时未处理的待办上限为 500（平台侧可配置）；漏发 `close` 的待办会被平台每日清理兜底，但**不能代替**在业务状态变化时调用 `close`。

更完整的说明（常见流程怎么表达、限制与排查）见 Kaiwu System 仓库的 `docs/UNIFIED_TODO.md` 与 ADR 0034。

### 站内信 `NotificationClient`

```java
notificationClient.send(order.getOwnerUserId(),
        "订单已超时", "订单 " + order.getOrderNo() + " 超过 24 小时未支付",
        "/order/" + order.getId());
```

收件人必须是本项目有效成员；同样是尽力而为，失败只记日志（ADR 0007）。

## 在业务服务中使用

请将 Spring Boot 主类放在业务自己的基础包中（例如 `com.example.crm`）。若基础包设为 `com.kaiwu`，组件扫描会直接装配 Starter 的文件或 Agent 接口，绕过相应的自动配置开关。

正式版本发布在 Maven Central，无需额外配置仓库：

```xml
<dependency>
  <groupId>com.kaiwuzaidao</groupId>
  <artifactId>kaiwu-system-starter</artifactId>
  <version>0.2.0</version>
</dependency>
```

需要行级数据范围时再加同版本的可选组件（见 [data-scope/README.md](data-scope/README.md)）：

```xml
<dependency>
  <groupId>com.kaiwuzaidao</groupId>
  <artifactId>kaiwu-data-scope</artifactId>
  <version>0.2.0</version>
</dependency>
```

两者版本保持一致。平台项目工厂生成的后端项目已按这一版本写好依赖。

## License

本项目基于 [Apache License 2.0](LICENSE) 开源。

## Agent 能力自动注册

随 `0.2.0` 提供默认关闭的 Agent 注册客户端，支持启动注册、心跳、有界退避和凭据/版本错误停止。采用独立注册凭据，不改变现有用户 Context 验证，也不开放远程执行。见 [接入与状态说明](docs/AGENT_REGISTRATION.md)。

## Excel 导入导出（可选）

业务项目可按需使用本仓独立的 `kaiwu-excel` 组件，生成单 Sheet 模板、读取并汇总行号与业务错误、写出文本表格。它不依赖数据库，也不负责授权和业务写入。依赖坐标、示例、限制和事务边界见 [excel/README.md](excel/README.md)。0.2.0 随 Starter 发布到 Maven Central。

## 行级数据范围（可选）

业务项目可按需使用独立的 `kaiwu-data-scope` 组件，让同一接口按角色返回不同行：`KaiwuContext.dataScope()` 解析 Gateway 的 `ds` 声明，组件据此自动改写 MyBatis-Plus SQL。接入三步、改写规则、公开 API 与启动自检见 [data-scope/README.md](data-scope/README.md)。
### 显式scope服务凭据（C5）

`auth.client.ScopedServiceClient` 是供后台显式调用的公共Bearer传输，不自动装配，不拦截正常用户请求。
构造时固定纯System服务原点、`Credential(serviceCode, scopes, token)` 与有界超时；每次调用固定代码定义的
`Endpoint(path, scope)`。实际权限以System数据库中的项目、服务、scope、状态和到期时间为准，不能信任客户端声明。
不发送用户Context，不读取旧delivery-token配置，不跟随重定向，不自动重试。成功/拒绝/不可用/正文超限/本地scope不符
由`Result.outcome`区分，调用方必须检查结果，不得把失败当作空业务成功。响应上限沿用原feature的32KiB，超限主动取消流。

旧`TodoClient`与`NotificationClient`仍保持现有构造器、线格式、缺配置跳过及失败不抛异常的尽力语义。
新的显式scope传输不会替换它们；构造参数错误会在装配时拒绝，网络失败返回不含凭据或远端错误正文的结构化结果。
本次不引入ProjectMemberClient、ManagedTodoClient或受限用户委托，它们属于后续独立步骤。

### 项目候选成员查询（C3）

`com.kaiwu.starter.members.client.ProjectMemberClient`供后台worker显式装配，无自动配置或用户请求拦截器：

```java
var members = new ProjectMemberClient(
    systemOrigin, projectCode, memberReadCredential,
    ServiceAddressResolver.direct(), Duration.ofSeconds(2));
var candidateIds = members.membersByRole("approver");
var eligibility = members.eligibility(candidateIds, "approver");
```

原点、项目编码及`member:read`凭据来自可信配置，不接受浏览器或模型生成的网络地址。`lb://`需传入相应解析器。
角色查询一次返回最多200人的完整候选；资格批量请求最多200项、按去重后的输入顺序逐项返回。
零成员是成功的空列表。拒绝、超限、网络异常、错误JSON、重复或丢失字段、错序资格等均抛出
`MemberQueryException`，HTTP错误可通过`httpStatus()`区分，网络/不可信正文为0；worker必须保留等待状态。
不得吞掉异常后继续审批、当成零候选或扩大旧候选快照。办理时仍依据真实Context与目标服务授权，不用本查询代替鉴权。

请求只带Bearer，不带用户Context或可信身份头，不回退旧delivery-token，不跟随重定向；响应上限32KiB，
超限及错误响应均主动取消流。旧TodoClient/NotificationClient的构造器、报文和尽力调用行为不变。
使用这些类型的服务需要同时升级编译和运行时的 Starter 依赖。

### 受管待办投递（C2）

`todo.client.ManagedTodoClient`由后台worker显式创建，使用独立的`todo:managed`服务凭据，不自动装配，不挂在用户请求链路上：

```java
var client = new ManagedTodoClient(
    systemOrigin, projectCode, managedCredential,
    ServiceAddressResolver.direct(), Duration.ofSeconds(2));
var result = client.putManaged(persistedOperation);
```

`ManagedTodoOperation`应在业务事务中持久化，重试复用同一sequence、operationCreatedAt、正文和dueAt；时间使用UTC微秒精度。
每个目标是单个收件人。缺配置返回NOT_CONFIGURED，网络/可重试拒绝返回RETRYABLE_FAILURE，其他拒绝返回PERMANENT_FAILURE；
它们都不能标记投递成功。客户端不抛出普通传输异常、不自动重试、不回退旧delivery-token、不跟随重定向。
错误状态和超过16KiB的响应主动取消流，错误正文和凭据不进入结果。

只有APPLIED/DUPLICATE可确认对应操作，发送端仍需核对服务端版本、状态及payloadDigest。SUPERSEDED表示服务端已有更高版本；
`getManagedReceipt`返回STORED仅表示存在投影，不能直接确认任意本地版本。单次OPEN失败不会禁用后续终态关闭。
旧TodoClient/NotificationClient及其配置、线格式和尽力调用行为保留。System 协议开关默认关闭。

### 受限 Context 委托（C4）

只有调用方已按 D06 完成服务签名、原始路径及 nonce 校验后，显式 S2S 入口才可调用 `GatewayContextVerifier.verifyDelegatedSource`。来源 audience 和允许集合必须来自服务端可信登记，不能取请求自报值；它只验 Gateway Context，不表示发起权限或服务委托已通过。随后业务服务先校验绑定的 `initiatePermission`，再调用 `requireDelegation` 与可信登记的完整元组逐项比较。普通 `verify` 和 Agent 校验仍只接受本服务原有 audience；不要将受限入口挂到普通浏览器请求。


C4 没有自动配置、路径豁免或全局 audience 开关。接入方必须在明确登记的 S2S Handler 自行完成以下顺序，任一步失败立即停止且不写业务数据：

1. 按 D06 校验唯一头、有效 kid、原始 method/path/query、时间及原始正文和 Context 摘要的服务签名。
2. 在所有接收副本共享的持久存储中原子占用 nonce，至少保留五分钟；库不可用失败关闭。
3. 以该端点可信登记中的单一来源 audience 调用 `verifyDelegatedSource`，取得真实来源的用户、项目和权限。
4. 从已发布业务类型绑定取得必填 `initiatePermission`，检查已验证 Context 的权限，再检查目标资源归属和状态。
5. 用经过服务认证的 caller、已验证 Context 的 audience/projectCode、服务端固定的 target/endpoint/scope，以及已验签正文的 businessType 构造 `DelegationBinding`，调用 `requireDelegation` 与服务端允许集合精确比较；随后才进入业务事务。

两方法是调用方编排所需的局部校验原语，不能证明此前已验签、已占用 nonce 或已做领域授权；不得将外部自报元组同时放入 observed 和 allowed。`requireDelegation` 不从 audience 字符串推导服务名，目标服务和端点由接入方可信配置保证。普通 `StarterAuthInterceptor` 仍使用 `verify`，不会放行这类来源 Context；生产接入方式应单独审查，不扩大普通路径或通配受众，也不要将来源 Context 填入普通请求级 `StarterContext`。

本步测试含临时 RSA/HMAC、双 HTTP 参考接收副本及真实共享 MySQL nonce，覆盖 Context 替换、重放、无权限和存储故障；参考接入方只位于测试源码，不是已注册的 Workflow 服务。实际 Workflow 幂等事务、业务快照/正文校验及真实 Gateway 联调仍须后续验收。旧单服务 audience、Agent 用途隔离、TodoClient/NotificationClient 和 C5/C3/C2 代码保持原行为；C5 多凭据开关仍须保持默认 false。

回滚先停止发起与回调 worker，停用调用新增方法的 S2S 入口并撤销相应登记，再回撤本步 SDK；保留共享 nonce、审计、业务幂等账本及所有新增字段和墓碑。不得退化为跳过签名/重放或全局信任同项目 audience。若消费方已编译调用新方法，须先升级为不调用这些方法且拒绝相关入口的兼容制品，避免旧 SDK 的 NoSuchMethodError。


### 文件证据保留（可选扩展）

业务项目先以新增迁移应用 `docs/storage-file-retention.sql`，再开启 `kaiwu.starter.storage.files.retention-enabled=true`。所有实例及文件管理/清理进程必须使用同一启用配置，不能在存在保留引用后回滚为旧版删除实现或关闭保护。旧二进制并不知道保留协议，混合部署前应暂停删除和purge。

经业务资源授权后调用 `StoredFileService.retain(fileId, ownerService, reference)` 建立幂等持久引用，普通删除与purge不能删除它。普通管理接口不提供释放。可信维护流程仅在同事务确认无业务引用且无迟到提交后，调用release(fileId,ownerService,reference)精确释放；证据仍被引用时不得调用。ownerService与reference必须来自可信服务逻辑，不能让客户端任意声明。对仍有可用预签名PUT的原附件，必须先创建可信不可变副本再保留，retain本身不撤销存量上传URL。

登记回执不确定时对象保留并记录可核对身份；没有确认未引用之前不自动清理。保留扩展不等同于已提供安全上传幂等协议或跨服务附件冻结接口。

引用的 `ownerService` 和 `reference` 按大小写精确区分；新增表使用 ASCII 二进制排序规则。若项目曾试用不区分大小写的旧扩展表，须在停止全部删除/清理/写引用进程后，用新的向前迁移将这两列改为 `CHARACTER SET ascii COLLATE ascii_bin`，保留原行并先核对历史别名。`CREATE TABLE IF NOT EXISTS` 不会升级已有表。SDK 会拒绝旧索引别名冲突造成的虚假保留成功，不能用关闭保护规避。

可信后台任务可先调用 `reserveEvidence(id, ownerService, reference, size, sha256)` 持久预留身份，提交事务后在事务外调用 `uploadEvidence(id, stream)`。一次性 CAS 为 `RESERVED → UPLOADING`，只有取得写权者才 PUT；重试仅重读并逐字节验证原对象的长度与 SHA-256，匹配后激活并建立持久引用。普通文件名/MIME 为 `evidence.bin` / `application/octet-stream`。ID、所属服务、引用、长度或摘要不一致须人工核对或使用新尝试身份，不覆盖原对象。`RESERVED` / `UPLOADING` 不参加普通过期清理，结果不确定时保留登记和对象，避免误删可能已提交的证据。该协议需要启用引用保护的 JDBC 实现；旧自定义仓库新增方法默认不支持，不能宣称已获得证据保留能力。

普通中转上传的数据库登记异常也可能是提交成功后回执丢失，因此会保留对象和可核对身份，继续向调用方抛出原失败；这是本步对旧补偿删除行为的修正。维护流程须先证明没有引用与迟到提交，再清理，不能凭异常直接删对象。保留引用不会撤销已有预签名 PUT，对外可写原附件必须先复制为可信不可变证据。文件服务本身不替业务做资源归属/状态授权，也不自动提供跨服务冻结或释放端点。

应用前先停止全部旧新 worker、文件删除和清理，备份并新增业务项目迁移，核对表及排序规则，再统一升级所有执行面并授权开启保护。本次只提供 SDK 与迁移示例，未对任何业务库执行迁移或开启运行配置。已有引用后不能直接回退旧 JAR 或把 `retention-enabled` 设为 false；若需撤回本步调用，先停 worker 和相关入口，保留引用表、文件状态、原对象、业务幂等字段及墓碑，使用继续执行保留保护的兼容制品。引用清理必须由可信维护流程在业务事务内确认无人再引用后，按完整身份精确 release；普通管理员删除权限不授予释放权。

`purgeExpired` 也须由事务外的任务调用；JDBC 领取会加入当前同源事务，若调用方自行包裹长事务，会把文件行锁带到对象网络 IO，接入方必须避免这种调用方式。


## 能力应用的多项目 Context（ADR 0046）

默认使用单项目校验。平台安装的共用能力应用可显式设置 `kaiwu.starter.tenancy=multi-project`。
由受控部署配置提供以下 `kaiwu.starter.multi-project.*` 配置：

| 配置 | 来源 |
| --- | --- |
| `service-code` | 平台登记的目标服务编码 |
| `app-code` | 平台受控应用编码，匹配 `[a-z][a-z0-9-]{1,63}`，与部署证明逐字相等 |
| `deployment-id` | 平台分配给该部署的 ID，匹配 `[a-z0-9][a-z0-9-]{7,63}` |
| `release-digest` | 当前运行发行物对应的整包 SHA-256，64 位十六进制 |
| `deployment-proof` | 平台签发的部署 JWS |
| `deployment-public-keys` | 平台生命周期 RSA 公钥，按非空 `kid` 配置，启动校验全部 1–2 把 RSA SPKI 公钥，每把至少 2048 位，且与 Gateway 公钥独立 |
| `registry-timeout` | 登记读取时限，默认 `500ms`，可配置到 `30s` |

应用提供一个 `TrustedProjectRegistry` Bean，按项目编码直读本部署的权威主库。返回规范项目 ID、
唯一 audience、revision 和准入状态，未登记返回 `Optional.empty()`，查询故障抛异常。
通过校验的准入由 `StarterContext.require().admission()` 取得，应用按准入和当前状态约束具体操作。
提交登记变更后，各副本调用 `GatewayContextVerifier.invalidateProjectRegistration(projectCode)`。
吊销部署时先提交全量拒绝状态，再调用 `invalidateProjectRegistrations()` 清除整个部署的本地缓存。
正负缓存均从读取开始最多保留 30 秒。多项目准入仅用于普通 Gateway Context，受限委托与 Agent 入口保留单项目绑定。


## 能力应用生命周期断言（ADR 0045 §4a）

能力应用提供持久的 `LifecycleNonceStore` Bean 后，Starter 自动装配独立的
`LifecycleAssertionVerifier`。独占实例同样可用。它读取上述 `app-code`、`deployment-id`
及 `deployment-public-keys`，离线验证 `kaiwu-app-lifecycle+jwt`。轮换时通过受控部署流程更新
公钥和部署证明，并重建校验器（或重启各副本），逐副本确认实际加载的 `kid` 和指纹。

固定生命周期处理器在路由重写、URL 解码和正文解析之前捕获原始请求，构造 `LifecycleRequest`。
输入包括方法、原始路径及查询串、移除传输分帧后的原始字节、所有同名 `X-Kaiwu-Lifecycle` 头、
所有 `Content-Encoding` 头，以及从应用主库读取的路径项目的 `registeredProjectId`。
读取正文时最多读 `LifecycleRequest.MAX_BODY_BYTES + 1` 字节，超限在哈希前返回 413。
任何 `Content-Encoding`（包括 `identity`）都返回 415。不可用框架归一化后的路径代替原始目标。

校验器依次验证 RS256、严格 JOSE/JSON/base64url、UTC 整秒时间窗、部署 audience、scope、
方法/目标/正文摘要和重复身份字段，然后原子占用 nonce。成功返回的 `VerifiedLifecycleAssertion`
包含 `operationId`、可选 `projectId`、路径 `projectCode`、scope 及到期时间。无效凭据、项目或目标
错配、重复 nonce 返回 401；存储异常、不确定和空返回都返回 503。异常使用固定消息且无底层原因链。

`registeredProjectId` 只能来自应用的可信登记，不能取自正文、查询或普通头。已登记项目必须传该 ID。
无登记时可传 null，但应用必须按 ADR 执行首次启用、未登记只读查询或此前已提交操作的台账重放限制。
登记绑定的唯一性、状态/revision 守卫和同一 operationId 的领域幂等仍由应用负责。

默认消费白名单为以下精确组合，应用可通过构造器提供其中子集。

| 方法 | 路径 | scope |
| --- | --- | --- |
| GET | `/internal/apps/uninstall-blockers` | `app:lifecycle:read` |
| GET | `/internal/apps/projects/{projectCode}/enablement` | `app:lifecycle:read` |
| PUT、DELETE | `/internal/apps/projects/{projectCode}/enablement` | `app:lifecycle:write` |
| POST | `/internal/apps/projects/{projectCode}/freeze`、`/internal/apps/projects/{projectCode}/unfreeze` | `app:lifecycle:write` |

只有阻塞查询 GET 允许 `cursor`（解码后最多 256 字符）和 `limit`（1–500，无前导零），查询名按
ASCII 升序排列。cursor 按 RFC 3986 规范 UTF-8 编码。应用响应使用 `nextCursor` 和 `complete`
表达分页完整性。所有其他端点按原认证处理，生命周期头不能用于授予业务访问权限。

生产 SPI 由应用库实现，以 `(issuer, deploymentId, jti)` 唯一键原子插入，先独立提交成功再返回
`CLAIMED`。唯一冲突返回 `REPLAY`，无法确认提交结果返回 `UNKNOWN` 或抛异常。占用不得随领域事务
回滚或失败而删除。传入的 `retainUntil` 为 `exp+10` 秒，覆盖验证容差及最多 5 秒验证时钟落后。
应用清理任务还须补偿自身可能领先的时差，确保所有验证副本都越过 `exp+5` 后才删除。
Starter 的内存实现只存在于测试源码中。

部署按 ADR 的第一期约束使用同一宿主机专用 Docker 网络和 Compose 服务名。Gateway 的 R4 内部路径
404 与入站生命周期头剥离由 A1 落实，应用入口集成及数据库 SPI 由 T2 落实。本 SDK 负责验证契约。
