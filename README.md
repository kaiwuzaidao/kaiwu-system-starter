# Kaiwu System Starter

业务服务接入 SDK，只负责：

- 本地验证 Gateway 签发的 RS256、audience-bound Context。
- 建立并清理 `StarterContext`。
- 执行 `@RequirePermission("mod:res:act")`。
- 允许 System 通过 `PermissionResolver` 覆盖平台权限来源。
- 可选启用项目 scheduler：只同步凭据绑定项目的配置，抢占中央租约后在业务进程内执行。

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

## 在业务服务中使用

当前制品：

```xml
<dependency>
  <groupId>com.kaiwuzaidao</groupId>
  <artifactId>kaiwu-system-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

`0.1.0` 尚未发布到 Maven Central（计划中，groupId `com.kaiwuzaidao` 走域名验证）。
在那之前先构建并安装到本机 Maven 仓库：

```bash
mvn -s .mvn/settings.xml install
```

正式公开发布前需要补齐许可证、开发者、SCM 和签名等 Maven Central 元数据。

## License

本项目基于 [Apache License 2.0](LICENSE) 开源。
