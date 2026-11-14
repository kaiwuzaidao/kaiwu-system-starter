# Kaiwu Data Scope（可选组件）

行级数据范围：同一个菜单、同一个接口，不同角色看到、修改的行不同。模块按 Gateway 签发的 Context 声明 `ds`
自动改写 MyBatis-Plus 的 SQL，**默认生效**，不依赖开发者逐个方法记得加条件。设计依据见平台 ADR 0044 与
`docs/DATA_SCOPE.md`。

## 接入三步

1. **依赖**（业务后端 domain 模块）：

```xml
<dependency>
  <groupId>com.kaiwuzaidao</groupId><artifactId>kaiwu-data-scope</artifactId><version>0.2.0</version>
</dependency>
```

2. **注册拦截器，必须在分页之前**：

```java
@Bean
public MybatisPlusInterceptor mybatisPlusInterceptor(ObjectProvider<DataScopeInnerInterceptor> dataScope) {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    dataScope.ifAvailable(interceptor::addInnerInterceptor);
    interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
    return interceptor;
}
```

3. **声明受控资源**：`kaiwu/data-scopes.yml`，并打包到 classpath 的 `META-INF/kaiwu/data-scopes.yml`。受控表必须有
`owner_user_id`、`owner_dept_id`（`BIGINT NOT NULL`，各一个单列索引），实体上这样声明：

```java
@TableField(fill = FieldFill.INSERT, updateStrategy = FieldStrategy.NEVER)
private Long ownerUserId;
@TableField(fill = FieldFill.INSERT, updateStrategy = FieldStrategy.NEVER)
private Long ownerDeptId;
```

没有自己的 `MetaObjectHandler` 时模块会提供一个；已有时在 `insertFill` 里先调用 `DataScopeOwnerFiller.fill(metaObject)`。

## AI Skill

[skill/SKILL.md](skill/SKILL.md) 是给 AI 编程工具用的 `kaiwu-data-scope` Skill 源文件（与代码同版本）。项目工厂生成项目时
会把它写入 `.agents/skills/kaiwu-data-scope/SKILL.md`，并在 `.claude/skills/` 放转发指针；已有项目复制该文件到同一位置即可。
生成项目里的 `DataScopeConventionTest` 是配套门禁。

**同步约定**：System 仓的模板 `skill-kaiwu-data-scope.md.ftl` 是该文件的拷贝，修改任一处都要同步另一处。

## 它做什么

| 语句 | 受控表出现时 |
|---|---|
| `SELECT`（含子查询、UNION、分页 count） | 对该表别名追加 `(owner_user_id = ? OR owner_dept_id IN (…))` |
| `INNER JOIN` 两张受控表 | 谓词进 WHERE，结果取交集 |
| `LEFT JOIN` 受控表 | 谓词进 ON，左表行保留 |
| `UPDATE` / `DELETE` | WHERE 追加同一谓词，越权行影响 0 行 |
| `UPDATE … SET owner_*` | 拒绝，只能走 `DataScopes.reassign` |
| `INSERT … VALUES` | 不改写；`DataScopeOwnerFiller` 以 Context 覆盖请求体传入的归属 |
| `INSERT … SELECT` | SELECT 部分加谓词 |
| `ON DUPLICATE KEY UPDATE` / `REPLACE` / `MERGE` | 拒绝（`unrestricted` 内除外） |

所有取值都是绑定参数。没有 Context 的线程触达受控表、SQL 无法解析、Context 里没有该资源的规则时，
前两者抛 `DataScopeViolationException`，后者按「仅本人」处理，不放行。

## 公开 API

```java
DataScope scope = DataScopes.current();                    // 极少用：例如缓存键带 scope.fingerprint()

DataScopes.unrestricted("每日客户公海回收任务", () -> mapper.selectExpired());

DataScopes.reassign("销售 A 调岗交接", new DataScopes.Owner(newUserId, newDeptId),
        () -> mapper.updateOwnerByUser(fromUserId, newUserId, newDeptId));
```

`reason` 必填并写入 INFO 日志。`reassign` 的源行仍受当前用户范围约束；新归属人必须是项目有效成员、新部门必须是
其所属部门之一，由 `MemberDirectory` 校验，成员查询失败时一律拒绝，不会当作「不是成员」。没有 `@DataScopeIgnore` 注解。

**成员查询的默认实现**：配置 System 地址和带 `member:read` 范围的项目服务凭据即自动装配
`SystemMemberDirectory`（调用 System 的 `POST /api/internal/projects/{code}/members/placement`，只在归属转移时调用）：

```yaml
kaiwu:
  starter:
    project-code: crm
  data-scope:
    members:
      system-address: lb://kaiwu-system-service   # 或 http(s)://…
      credential: ${KAIWU_MEMBER_CREDENTIAL}       # 绑定本项目、带 member:read
```

业务侧也可以自己注册 `MemberDirectory` Bean，默认实现会让位。两者都没有时 `reassign` 一律失败。

## 启动自检

任何一项不满足，应用无法启动：拦截器没有加入 `MybatisPlusInterceptor` 或排在分页之后；清单里的表、列不存在；
受控实体缺少归属字段、没有声明 `fill = INSERT` 或 `updateStrategy = NEVER`；归属字段没有被任何
`MetaObjectHandler` 填充；`META-INF/kaiwu/data-scopes.yml` 缺失（确实不需要请设置 `kaiwu.data-scope.enabled=false`）。

## 不自动覆盖的路径

`JdbcTemplate` 等绕过 MyBatis 的访问、缓存（含 MyBatis 的 `<cache/>` 二级缓存：缓存键不含数据范围，对受控 Mapper 不要启用）、ES 等外部检索、详情查不到时应统一返回 404，见设计稿 §8.6；
这些靠生成项目里的 `DataScopeConventionTest` 与 AI Skill 约束。
