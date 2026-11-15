# Agent 自动注册客户端（A2）

0.2.0 的注册客户端随 `com.kaiwuzaidao:kaiwu-system-starter` 提供，依赖配置见 [README](../README.md#在业务服务中使用)。SDK 自包含协议 DTO，不依赖 System API/Domain/数据库。本页只说明注册与心跳；固定的[只读 Handler](AGENT_CAPABILITIES.md)和[写 Handler/事务账本](AGENT_WRITE_LEDGER.md)是独立启用的执行能力，注册成功本身不会开放调用。

## 配置与声明

默认关闭。普通 HTTP 业务请求不回调注册服务；显式启用时检查配置并异步注册。System 必须已部署支持 Agent 注册和心跳的服务端，独立注册凭据由具备平台项目编辑权限及目标项目管理员角色的用户签发。

```yaml
kaiwu:
  starter:
    project-code: crm
    agent:
      enabled: true
      system-base-url: ${KAIWU_SYSTEM_SERVICE_URI}
      credential: ${KAIWU_AGENT_REGISTRATION_CREDENTIAL}
      heartbeat-seconds: 60
      timeout-millis: 5000
```

`system-base-url` 是 System 内部根地址，只接受统一寻址协议，不可含路径、用户名密码、查询或片段。`http(s)://` 直接寻址；`lb://` 使用已有 ServiceAddressResolver，每次请求重新选择实例。启用时会校验解析器可用性（无 LB 依赖/可用实例会启动失败），不会发起同步注册 HTTP。注册服务暂不可达时业务启动不被 HTTP 等待阻塞。

注册凭据必须为 System 签发的 `kagent_` 值，不能复用调度凭据、JWT 或 Context。密钥来自服务秘密配置，不提交到 Git，不在浏览器持久存储，也不要把凭据对象或配置绑定结果写日志。轮换后更新配置并重启客户端，旧凭据立即失效。本版不做动态凭据热更新。

每个能力作为独立 Bean 声明，单应用 1..100 个，编码不可重复，也不可跨越配置的项目命名空间：

```java
import com.kaiwu.starter.agent.protocol.*;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;

@Bean
CapabilityManifest recordListManifest() {
    return new CapabilityManifest(
        "crm", "crm.record.list", "1", "查询当前用户可见的客户记录",
        "record:record:list", DataScope.SELF,
        Map.of("pageSize", ParamSpec.pageSize(100, 20)),
        Set.of("id", "title"), Map.of(), true, true, ConfirmationMode.NONE, 5000);
}
```

权限必须先在 System 的目标项目中登记为有效 BUTTON。示例只登记元数据，不会自动把现有 Controller 或 Service 暴露给模型；接入可执行能力时，应由同一个 Handler Bean 提供固定 manifest 与领域实现，不要重复声明独立 manifest Bean。

## 生命周期

- 进程启动后加最多 1 秒抖动注册。成功后使用 System 返回的版本摘要发送心跳，默认 60 秒另加 0..5 秒抖动；System 在线窗口为 180 秒。
- 每能力至多一个请求进行中，2 个受管调度线程；请求与响应均最多 64 KiB，整次 HTTP 包含响应体受超时约束。禁止 HTTP 重定向，防止凭据被转发到新地址。
- 连接错误、超时、429 或 5xx 使用指数退避加抖动，最长 60 秒。持续故障可持续重试，但频率与任务数有界；不把失败解释为在线或已批准。
- 401/403 停止整个客户端；其余非暂态 HTTP 错误（包括 400、404、409、413、重定向）停止该能力。修正凭据、部署或版本问题后显式重启，不自动用旧清单覆盖新版本，也不自动恢复撤销记录。
- `AgentRegistryLifecycle.states()` 仅供本地运维：REGISTERED 表示注册/心跳成功，**不表示 System 已审批，更不表示可以执行**。HTTP 200 的错误业务码、错项目/能力/版本、错摘要、超大或非法 JSON 均拒绝。
- 停机取消本进程任务和 HTTP 资源，不撤销共享目录中的能力。多副本共享同一能力，只有全部副本离线后才自然过期。管理员主动撤销需在平台执行。
- 变更 manifest 必须递增数字分段版本；同版改内容会被 System 拒绝。滚动发布时旧版本会因 409 停止该项心跳；新版本保持待审核，审批与注册分离。

后台错误仅记录状态码和本地阶段，不记录响应正文、URL、令牌或异常原文。协议测试夹具 `agent-registration-v1.json` 与 System 的同名夹具一致；HTTP 测试用回环服务器验证真实序列化、边界与总超时，不触碰共享环境项目。
