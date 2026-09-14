# Kaiwu System Starter Agent Contract

- 禁止直接在 `main` 等受保护分支实现；保留调用者当前分支，必要时创建描述性 feature/fix 分支。
- 保持 SDK 自包含，不添加平台业务模块。
- 鉴权失败必须 fail closed。
- `StarterContext` 必须在请求结束清理。
- 新增权限能力时同步 ADR 0003 与测试。
