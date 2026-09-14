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
- 发布到内部 Nexus 走 GitLab CI（`.gitlab-ci.yml`），不在本机 deploy：
  推默认分支由 `publish:snapshot` 自动发布 SNAPSHOT，下游业务项目据此解析依赖。
  tag 触发的 release 尚未接入，需要时按 `platform/ci-templates` 的
  `java-library-maven.yml` 补齐，并保证 tag 与 pom 版本一致。
  凭据只来自 CI 变量 `NEXUS_MAVEN_USER` / `NEXUS_MAVEN_PASSWORD`（与 ci-templates
  统一约定），settings 在 job 内临时生成，仓库内不得出现明文凭据或 truststore 密码。
- 基础镜像一律取自 Harbor：内网 runner 无法访问 Docker Hub，写死上游地址会导致
  拉取超时，CI 静默失败。
