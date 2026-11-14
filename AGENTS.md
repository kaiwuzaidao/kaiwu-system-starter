# Kaiwu System Starter Agent Contract

- 禁止直接在 `main` 等受保护分支实现；保留调用者当前分支，必要时创建描述性 feature/fix 分支。
- 保持 SDK 自包含，不添加平台业务模块。
- 鉴权失败必须 fail closed。
- `StarterContext` 必须在请求结束清理。
- 新增权限能力时同步 ADR 0003 与测试。
- `NotificationClient` / `TodoClient` 是尽力而为的出站客户端：必须永不抛异常、缺配置静默跳过、不得挂在请求链路上；
  新增出站调用沿用这一约定，并在 README「站内信与统一待办」补用法。

## 后端包结构与契约 v1

遵守 [PACKAGE_STRUCTURE.md](docs/PACKAGE_STRUCTURE.md) 与 [package-layout.json](docs/package-layout.json)。
分类在前、技术层在后，直接 `identity/controller/UserController`，不再插入功能目录。
公开 Java 类型按兼容清单保留；迁移不得改变权限、HTTP/Agent 协议和数据库语义。
布局门禁由 `PackageLayoutContractTest` 随 Maven 测试执行。


## Java 风格与中文 Javadoc

遵守 [docs/JAVA_STYLE.md](docs/JAVA_STYLE.md)。所有手写 Java 类型、构造器和方法均使用中文
Javadoc，说明职责、授权边界、状态迁移或副作用；禁止用复述签名的模板句充数。格式化统一使用
Spotless 的 Palantir Java Format；提交前运行 `mvn spotless:apply` 与本仓验证命令。
