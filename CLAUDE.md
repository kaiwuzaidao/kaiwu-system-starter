# Kaiwu System Starter 开发约定

先读工作区 `../CLAUDE.md`、`../docs/ARCHITECTURE.md` 和 ADR 0003。

- 本仓库是独立 Maven SDK，不是部署服务。
- 不得依赖 System API、Domain 或数据库。
- 只接受 Gateway Context；不得恢复共享 HMAC 用户 JWT 或同步回调 System。
- Context 必须校验 typ、issuer、唯一 audience、签名、有效期和项目绑定。
- 改动后执行 `./scripts/verify.sh`；脚本会先拒绝非 JDK 21 环境。
  该脚本跑 `mvn verify`，spotless 绑在 `validate` 阶段：排版不合规会在编译前就失败，
  连编译都到不了。修复只有一条命令 `mvn spotless:apply`（palantirJavaFormat），
  不要手工对齐——手工对齐的结果和 palantir 的输出几乎不会一致。
- 提交前的固定动作：`mvn spotless:apply && ./scripts/verify.sh`。依据 ADR 0029。

## 后端包结构与契约 v1

遵守 [PACKAGE_STRUCTURE.md](docs/PACKAGE_STRUCTURE.md) 与 [package-layout.json](docs/package-layout.json)。
分类在前、技术层在后，直接 `identity/controller/UserController`，不再插入功能目录。
公开 Java 类型按兼容清单保留；迁移不得改变权限、HTTP/Agent 协议和数据库语义。
布局门禁由 `PackageLayoutContractTest` 随 Maven 测试执行。


## Java 风格与中文 Javadoc

遵守 [docs/JAVA_STYLE.md](docs/JAVA_STYLE.md)。所有手写 Java 类型、构造器和方法均使用中文
Javadoc，说明职责、授权边界、状态迁移或副作用；禁止用复述签名的模板句充数。格式化统一使用
Spotless 的 Palantir Java Format；提交前运行 `mvn spotless:apply` 与本仓验证命令。
