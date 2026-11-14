# Data Scope D3 验证记录（Starter：`KaiwuContext.dataScope` + `kaiwu-data-scope`）

依据：System 仓 `docs/DATA_SCOPE.md` §4–§6、设计稿 §8、ADR 0044。分支 `feat/data-scope-d3`，基于 `origin/dev`。

## 交付

- **主 Starter**：`KaiwuContext.dataScope()`（旧 11 参构造器保留）；`ContextDataScope.parse` 对 `ds` 做
  结构、规范化顺序（部门数值升序、下标升序、资源码字典序）、引用完整性和**指纹重算**校验，任何不符都让 Context
  验证失败。Agent `DataScope` 新增 `ROLE_SCOPED`，类注释改写，`docs/AGENT_CAPABILITIES.md` 同步。
- **`data-scope/` 模块**（artifactId `kaiwu-data-scope`，与 `excel/` 同样独立发布，CI 已接入 verify 与 SNAPSHOT 发布）：
  `DataScopeRegistry`、`DataScopeInnerInterceptor`（内部 `ScopeRewriter`）、`DataScopeOwnerFiller`、
  `DataScopes`（`current` / `unrestricted` / `reassign`，reason 必填）、`DataScopeStartupVerifier`、
  `DataScopeViolationException`、`MemberDirectory`、自动配置。

## 结果

- 主库 `./scripts/verify.sh`：197 个测试，0 失败（4 个跳过为既有）。
- 模块 `mvn verify`：43 个测试，0 失败，其中 Testcontainers MySQL 8.4 真实执行。
- 共用测试向量（System 契约 §4.4）：`d361b479f54b4be2`，Starter 侧断言一致。
- EXPLAIN（2 万行，2000 用户 × 200 部门）：`type=index_merge`，`key=idx_owner_user,idx_owner_dept`，`rows=210`。
  两个单列索引都被用上。

## 设计稿 §8.2 逐行覆盖

| 行 | 用例 |
|---|---|
| SELECT 五种归约、selectById、count、分页 | `listsFollowTheReducedScope`、`selectByIdOfInvisibleRowReturnsNull`、`paginationCountMatchesVisibleRows` |
| INNER JOIN 取交集 / LEFT JOIN 谓词进 ON | `innerJoinIntersectsBothTables`、`leftJoinKeepsLeftRowsAndNullsInvisibleRightRows` |
| 子查询、UNION | `subqueriesAndUnionsAreFiltered` |
| UPDATE/DELETE 越权 0 行 | `updatesAndDeletesOnInvisibleRowsAffectNothing`（含 Wrapper 的 `updateById`/`deleteById`） |
| `SET owner_*` 拒绝，reassign 内允许，源行越权 0 行 | `ownerColumnsChangeOnlyThroughReassign`、`batchHandoverOnlyTouchesVisibleRows` |
| reassign 校验：非成员、非所属部门、0、reason、无 Context、无 MemberDirectory | `reassignValidatesTheNewOwner`、`reassignFailsClosedWithoutMemberDirectory` |
| ON DUPLICATE KEY / REPLACE 拒绝，unrestricted 内允许 | `upsertStatementsAreRejectedUnlessUnrestricted` |
| MERGE、多表 UPDATE/DELETE、TRUNCATE | `ScopeRewriterTest.unsupportedShapesAreRefused`、`unrestrictedScopeSkipsRewriting` |
| INSERT … SELECT 源查询带谓词 | `insertSelectOnlyCopiesVisibleRows` |
| 无 Context 抛异常；unrestricted 放行；无 ds / 缺资源按 SELF；豁免表不受影响 | `threadsWithoutContextAreRefusedUnlessUnrestricted`、`missingDsOrMissingResourceMeansSelf` |
| 无法解析的 SQL | `unparsableSqlOnControlledTableIsRefused` |
| 插入：Context 覆盖伪造归属 / 无部门取 0 / reassign / unrestricted | `insertOverridesForgedOwnerFromContext`、`insertInsideReassignAndUnrestrictedScopes` |
| 谓词只用绑定参数 | `ScopeRewriterTest.predicateUsesBindParametersOnly`；`existingParametersKeepTheirOrderAroundTheInsertedPredicate`（LIKE ? … LIMIT ? 夹着新参数仍对） |
| 启动自检 | `DataScopeStartupVerifierTest`（未注册、顺序错、表/列缺失、Filler 未处理、注解缺失）、`DataScopeAutoConfigurationTest` |

## D6 联调发现并修复的缺陷

D6 端到端时发现：`BaseMapper.selectOne(wrapper)` 对越权用户返回了数据。根因是 MyBatis-Plus 3.5.17 的 `selectOne`
（以及游标、`ResultHandler` 查询）不经过 `Executor.query`，而本拦截器原先把 SELECT 的改写放在 `beforeQuery`，
这些路径整条绕过了数据范围，**且没有任何报错**。D3 的测试只覆盖了 `selectList`/`selectById`/`selectPage`/`selectCount`，没发现。

修复：SELECT/INSERT/UPDATE/DELETE 的改写统一放在 `StatementHandler.prepare`（`beforePrepare`），所有执行路径都会经过它；
分页的 count 与列表各自在这里被改写。新增回归用例 `selectOneWithWrapperConditionIsFiltered`、
`cursorAndResultHandlerQueriesAreFiltered`；模块测试 43 项通过。

## 关键事实与取舍

- **MyBatis-Plus 3.5.17**：`DataPermissionInterceptor` 对 SELECT 走 `beforeQuery`、对 UPDATE/DELETE 走
  `beforePrepare`，都能处理，不必另找方案；但它**不处理 INSERT**，且谓词只能是 `Expression`、没有绑定参数的口子。
  所以 `ScopeRewriter` 继承它借用子查询/JOIN/UNION/WITH 的遍历，自己处理 INSERT（`INSERT … SELECT`、
  `ON DUPLICATE`）。
- **绑定参数的做法**：谓词先以 `?1000000+k` 占位，改写后按 SQL 中 `?` 的出现顺序（跳过引号、注释）换回 `?`，
  并重排 `ParameterMapping`，数量对不上就抛异常。因此 `DataScopeInnerInterceptor` 必须在分页之前
  （启动自检会验证），分页 count 才基于改写后的 SQL。
- **`updateById` 与归属列**：MyBatis-Plus 默认会把实体上非空字段都写进 `SET`，会触发「禁止改 owner_*」。
  所以启动自检**强制**归属字段声明 `updateStrategy = FieldStrategy.NEVER` 与 `fill = INSERT`
  （设计稿没写，是为了让该约定可执行而加的）。
- **Filler 探测**：自检对每个受控实体的空实例跑一遍所有 `MetaObjectHandler.insertFill`，两个归属字段都被写入才算
  通过，用来发现「项目自带 Handler 但没有委托」。
- **未接入的拦截忽略**：不响应 `@InterceptorIgnore`，与设计稿「不提供注解旁路」一致。

## design-question（已解决）

`reassign` 的新归属校验需要「用户是否有效成员 + 所属部门」，平台原有成员查询（C3 的 `eligibility`）不含部门。
已确认由 System 新增 `POST /api/internal/projects/{code}/members/placement`（基于 C3 分支，分支
`feat/data-scope-members`，见该分支 `docs/verification/data-scope-members.md`）。本分支：

- `MemberDirectory` 改为单次 `placement(userId)`（成员资格与部门原子返回）；
- 新增默认实现 `SystemMemberDirectory`：有界、严格解析、任何故障都抛出；`reassign` 将其包装为
  `DataScopeViolationException`；
- 自动配置在配置了 `kaiwu.data-scope.members.system-address` 与 `credential` 时装配，业务侧自备 Bean 时让位。
- 依赖：System 的 `placement` 接口随 C3 一并合入前，默认实现无处可调；届时才能联调。

## 与契约的差异 / 补充

- 契约 §4.2 附加约束在 Starter 侧做了**更严**的校验：要求 `ds` 已是规范化形态并重算指纹。System 的生成与之一致
  （共用测试向量），但 Gateway 或第三方实现若不规范会被拒绝。
- 多表 `UPDATE`/`DELETE` 和 `TRUNCATE`/`MERGE` 在受控表上一律拒绝（设计稿未列出；谓词无法保证落在正确的表上）。
- 引入模块但缺 `META-INF/kaiwu/data-scopes.yml` 会启动失败（设计稿未规定），避免「接了模块却没保护」。

## 本次没有覆盖

- 达梦：只在 MySQL 上实测；谓词语法是通用的，但 JSqlParser 对达梦方言和元数据查询（大小写、schema）未验证。
- XML Mapper：用注解 SQL 与 BaseMapper 覆盖，XML 走同一个 `BoundSql` 路径，未单独写用例。
- `JdbcTemplate`、缓存、ES 等不经过 MyBatis 的路径（靠 D5 的 `DataScopeConventionTest` 与 Skill）。
- 并发/性能：未做压测；改写每条涉及受控表的语句都会解析一次 SQL，未加缓存。
- 该次验证使用本地构建的主库快照，先装入 `~/.m2` 供模块解析；CI 的 verify 阶段采用相同顺序。公开版依赖见[主库接入说明](../../README.md#在业务服务中使用)。
