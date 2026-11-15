# 对象存储

业务项目通过 `ObjectStorage` 读写文件，不直接依赖任何厂商 SDK。设计依据见 System 仓的 ADR 0039。

## 两种实现

| provider | 适用 | 说明 |
| --- | --- | --- |
| `local` | 本地开发、测试、体验版 | 存在本机目录，不支持预签名 |
| `kaiwu` | 生产：阿里云 OSS、MinIO 等 | 向 System 申请限定在本项目前缀的 STS 临时凭证，经 S3 协议读写 |

`kaiwu` 模式下，存储账号与项目绑定由平台管理员在 System「对象存储」页面维护，
业务项目不持有任何长期密钥。

## 接入 provider=kaiwu

1. 引入 AWS SDK（starter 中为可选依赖）：

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
    <version>2.56.0</version>
    <exclusions>
        <exclusion><groupId>software.amazon.awssdk</groupId><artifactId>netty-nio-client</artifactId></exclusion>
        <exclusion><groupId>software.amazon.awssdk</groupId><artifactId>apache-client</artifactId></exclusion>
    </exclusions>
</dependency>
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>url-connection-client</artifactId>
    <version>2.56.0</version>
</dependency>
```

2. 配置（凭据与 `kaiwu.starter.config.credential` 是同一个项目服务凭据，走环境变量注入）：

```yaml
kaiwu:
  starter:
    project-code: order-center
    storage:
      enabled: true
      provider: kaiwu
      max-object-size: 20MB          # 与平台绑定上配置的上限取较小值
      kaiwu:
        system-base-url: ${KAIWU_SYSTEM_SERVICE_URI}   # 直连 System，不经网关；支持 lb://
        credential: ${KAIWU_PROJECT_SERVICE_TOKEN}
        storage-name: default
```

3. 在 System「对象存储」页面把项目绑定到存储账号。

缺少 SDK、地址或凭据时应用启动失败；System 暂时不可达不影响启动，凭证到手前存储操作会失败。

## provider=local

```yaml
kaiwu:
  starter:
    project-code: order-center
    storage:
      enabled: true
      provider: local
      local:
        root-directory: /data/kaiwu-objects   # 多副本部署必须是共享目录
```

## 使用

```java
// 写入：总是落到当前生效的绑定
String key = keyPolicy.generate("invoice", file.getOriginalFilename());
StoredObject stored = storage.put(key, file.getInputStream(), file.getSize(), file.getContentType());
// 业务记录只保存 stored.storageRef() 与 stored.objectKey()，不保存完整 URL

// 读取：按记录里的 storage_ref 找回原绑定，平台切换存储后旧文件仍可读
ObjectStorage original = resolver.forRef(record.getStorageRef());
URI url = original.presignGet(record.getObjectKey(), Duration.ofMinutes(10));
```

## 约定

- key 由 `ObjectKeyPolicy` 生成，格式为 `{projectCode}/{业务类型}/{yyyy/MM}/{uuid}.{扩展名}`。
  越出本项目前缀的 key 一律拒绝，与临时凭证的前缀限制一致。
- `ObjectStorage` 不做业务权限判断；读写前由业务确认当前用户有权访问对应记录。
- 写入超过大小上限、类型不在平台白名单、实际长度与声明不一致、绑定为只读时拒绝。
- 预签名地址的有效期会被截断到临时凭证剩余时间之内。
- `supportsPresignedUrls()` 为 `false` 时（`local`），业务回退为后端中转上传和下载。
- 预签名直传完成后，必须用 `stat` 复核大小与类型，不能只信任前端回报。
- 凭证在启动时预取、后台续签，业务请求不同步调用 System。

## 项目内统一文件记录（ADR 0041）

默认关闭。只用 `ObjectStorage` 时，文件的存在只体现在业务表的两列里，没有统一的「有哪些文件」。
打开后，每个项目库里多一张 `kaiwu_file` 表，业务表只存文件 ID。

```yaml
kaiwu:
  starter:
    storage:
      enabled: true
      files:
        enabled: true          # 需要项目有数据源，并已按 docs/storage-file.sql 建表
        upload-ttl: 15m        # 直传地址有效期
        download-ttl: 5m       # 下载地址有效期
        pending-ttl: 24h       # 直传后超过该时间未确认视为孤儿
        deleted-retention: 7d  # 软删除后保留多久才真正删除对象
```

```java
// 后端中转上传：写入对象并登记。登记结果不确定时保留对象，供后续核对
StoredFile file = files.upload("invoice", multipart.getOriginalFilename(), multipart.getContentType(),
        multipart.getInputStream(), multipart.getSize(), userId);
files.bind(file.id(), bizRecordId);              // 挂到业务记录；业务表只保存 file.id()

// 前端直传：先登记并取上传地址，上传完成后确认
PendingFileUpload pending = files.prepareDirectUpload("invoice", "a.png", "image/png", size, userId);
// ... 前端按 pending.upload() PUT ...
StoredFile confirmed = files.confirm(pending.file().id(), userId);  // 复核大小与类型

// 下载：支持预签名时给直连地址，否则后端中转
Optional<URI> url = files.presignDownload(file, Duration.ofMinutes(5));
```

- **文件管理页接口**：`GET /api/files`（`file:record:list`）、`GET /api/files/{id}/download`（`file:record:download`）、
  `GET /api/files/{id}/content`（中转下载）、`DELETE /api/files/{id}`（`file:record:delete`）。上传不在此暴露。
- **业务权限仍由业务把关**：`StoredFileService` 不判断谁能看哪个文件；管理页的权限码只授予项目管理员。
- **孤儿与误删**：待确认超时、软删除过保留期的记录由定时任务 `kaiwu.file.purge` 清理，先删对象再删记录；
  在 System 的定时任务页面配置该任务类型（建议每小时一次）。
- **业务记录删除时**：由业务决定连带 `delete`，还是保留文件只解除关联。
- 业务可以自己声明 `StoredFileRepository` Bean 替换默认的 JDBC 实现。
