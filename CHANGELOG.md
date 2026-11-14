# Changelog

本项目采用语义化版本。所有值得用户关注的变化都记录在此文件。

## Unreleased

## 0.2.0 - 待定

- 配套 System 的 V127 基线只用于平台数据库。升级 Starter 时，业务项目继续使用自己的迁移历史；启用文件登记的项目仍按 `docs/storage-file.sql` 在业务库准备表结构。（ADR 0047、0041）

- 请将 Spring Boot 主类放在业务自己的基础包中（例如 `com.example.crm`）。若基础包设为 `com.kaiwu`，组件扫描会直接装配 Starter 的文件或 Agent 接口，绕过相应的自动配置开关。

- 首次在 Maven Central 同版本发布可选组件 `com.kaiwuzaidao:kaiwu-data-scope`；版本号跳过内部使用的 0.1.1、0.1.2，与平台 v0.2.0 对齐。
- 首次在 Maven Central 发布可选组件 `com.kaiwuzaidao:kaiwu-excel:0.2.0`，业务项目可独立引入 Excel 导入导出。
- 新增行级数据范围（ADR 0044）：`KaiwuContext.dataScope()` 解析 Gateway 的 `ds` 声明（结构、规范化顺序与指纹不符则 Context 验证失败）；Agent `DataScope` 新增 `ROLE_SCOPED`；新增独立组件 `kaiwu-data-scope`，按表自动改写 MyBatis-Plus SQL，提供 `DataScopes.unrestricted/reassign` 与启动自检。
- 新增项目内统一文件记录（默认关闭）：`kaiwu_file` 表（`docs/storage-file.sql`）、`StoredFileService`（后端中转上传、直传登记与确认、挂载业务记录、软删除、孤儿清理）、`/api/files` 管理接口与定时清理任务 `kaiwu.file.purge`（ADR 0041）。
- 对象存储新增 `provider=kaiwu`：向 System 申请限定在项目前缀的 STS 临时凭证，经 S3 协议读写阿里云 OSS、MinIO 等；新增 `ObjectStorageResolver` 按 `storage_ref` 找回原绑定（ADR 0039）。
- 新增对象存储 `ObjectStorage`（默认关闭）：统一读写接口、项目前缀内的 key 策略 `ObjectKeyPolicy`，以及本地磁盘实现，用于开发、测试与体验版。
- 新增 `TodoClient`：业务服务向平台投递统一待办（打开、办理完成、取消，支持按业务键或按处理人关闭），复用站内信的 System 地址与投递凭据配置。
- 建立公开构建质量门禁与开源治理文档。
- 移除内部制品仓库和旧品牌配置。
- 升级 Spring Boot、Jackson 与 JJWT 安全补丁，并收紧配置对象可变性。
- 采用 Apache License 2.0。
