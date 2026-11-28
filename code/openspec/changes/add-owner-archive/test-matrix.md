# 货主档案（owner-archive）测试矩阵

- 变更：`add-owner-archive`（分支 `feature/add-owner-archive`）
- 依据：本变更 `specs/owner-archive/spec.md`（八条要求、**实际 20 条场景**）、`design.md` 的「后端设计」与「API 接口契约 1.11」、`humandesign/sysdesign/v2/00a-红线与不变量.md`（INV-06、INV-18、INV-19）
- 覆盖类型只有四种：**单测**（kernel／应用服务，纯 JVM）、**DB 约束**（连真 PostgreSQL，直接写脏数据）、**集成**（起容器、真 HTTP + 真库）、**并发**（多线程同库）。契约对账用**构建期断言**（读仓库里的契约产物）。
- 测试栈沿用现状：JUnit 5 + AssertJ + ArchUnit；`*Test` 由 surefire 跑、`*IT` 由 failsafe 跑，两者都在 `mvn verify` 里。**未引入任何新框架**，未改父 POM。
- 集成测试一律连真 PostgreSQL（`localhost:5433`，按单元分库），无 H2／内存库替身。

> 文档口径差异（待设计方收口）：`design.md` 的 Context 写「八条要求、十五条场景」，`spec.md` 实际是 **20 条场景**；本矩阵按 `spec.md` 原文逐条列。`spec.md` 六处「Scenario」之外无遗漏。

---

## 1 spec 八条要求的场景级矩阵

「覆盖类型」列按上表四种归类；「现有用例」列中 **【新】** 标记本次补的用例，未标记的为存量用例。
状态：`覆盖` = 有不变量级的可红断言；`部分` = 主链路有断言但某一分支无手段；`未覆盖` = 见第 5 节原因。

### R1 货主编号由系统生成且不可变

| 编号 | 场景 | 覆盖类型 | 现有用例 | 状态 |
|---|---|---|---|---|
| R1-S1 | 建档生成编号、状态置可用、记录创建人与时间 | 集成 + 单测 | `OwnerArchiveApiIT#建档主链路`、`OwnerArchiveWindowOneIT#第一窗口可建档并可读回`、`OwnerCommandServiceTest#建档`、`OwnerArchiveTest#建档`、kernel `OwnerNumberRuleTest` | 覆盖 |
| R1-S2 | 编号不随改档／停用／启用变化 | 集成 + DB 约束 | `OwnerArchiveApiIT#改档与版本冲突`、`OwnerArchiveApiIT#停用与启用`、`OwnerArchiveConflictIT#版本来回版本单调递增`【新】、`OwnerArchiveConstraintIT#编号与主键不可变`【新】、`OwnerArchiveConstraintIT#编号唯一`【新】 | 覆盖 |

### R2 货主档案只有可用与停用两态

| 编号 | 场景 | 覆盖类型 | 现有用例 | 状态 |
|---|---|---|---|---|
| R2-S1 | 新档默认为可用、全仓网可见（不按仓授权） | 集成 | `OwnerArchiveApiIT#建档主链路`（受理快照 `ACTIVE`）、`OwnerArchiveDataScopeIT#全域可见` | 覆盖 |
| R2-S2 | 状态不替代合同判定：合同未生效时档案仍可用，响应不含合同字段 | 单测（状态机）+ 构建期 | `OwnerStateMachineTest`（`ACTIVE + EDIT → ACTIVE` 等）、`OwnerContractDocumentTest#不含合同字段`【新】、`OwnerArchiveApiIT#建档主链路`（详情 `containsKeys` 精确到 13 个字段） | 覆盖 |

### R3 建档四项必填且唯一性只认编号与信用代码

| 编号 | 场景 | 覆盖类型 | 现有用例 | 状态 |
|---|---|---|---|---|
| R3-S1 | 四项必填逐项缺失被拒，`details[].field` 命中该字段 | 集成（入口）+ DB 约束（库层）+ 单测 | `OwnerArchiveApiIT#四项必填`、`OwnerArchiveConstraintIT#四项必填不可为空`【新】、`OwnerArchiveConstraintIT#字段形态由库层兜住`【新】、kernel `OwnerFieldsTest` | 覆盖 |
| R3-S2 | 信用代码重复被拒、不产生第二条档案 | 集成 + 并发 + DB 约束 | `OwnerArchiveApiIT#信用代码重复`、`OwnerArchiveConstraintIT#信用代码唯一且停用档占位`【新】、kernel `OwnerCreditCodeRuleTest` | 覆盖 |
| R3-S3 | 同名不阻断（不同信用代码两条都成功，靠编号区分） | 集成 + DB 约束 | `OwnerArchiveApiIT#同名不阻断`【新】、`OwnerArchiveConstraintIT#名称可重复`【新】、kernel `OwnerFieldsTest` | 覆盖 |
| R3-S4 | 改档带版本号：匹配才写入并递增，不匹配拒绝并提示数据已变更 | 集成 + 单测 + 并发 | `OwnerArchiveApiIT#改档与版本冲突`、`OwnerArchiveConflictIT#版本来回版本单调递增`【新】、`OwnerCommandServiceTest#版本冲突`／`#落库版本冲突`、`OwnerArchiveTest#版本冲突`、kernel `OwnerVersionRuleTest` | 覆盖 |
| R3-S5 | 停用态不可改档（须先启用） | 集成 + 单测 + DB 约束 | `OwnerArchiveApiIT#停用态改档`、`OwnerArchiveTest#停用态不可改档`、`OwnerStateMachineTest#停用态不可改档`、`OwnerArchiveConstraintIT#只放行两条状态迁移`【新】 | 覆盖 |

### R4 停用阻断新建但不阻断存量

| 编号 | 场景 | 覆盖类型 | 现有用例 | 状态 |
|---|---|---|---|---|
| R4-S1 | 停用生效：转停用、原因／操作人／时间留痕、此后新建被拒 | 集成 + DB 约束 | `OwnerArchiveApiIT#停用与启用`、`OwnerStatusOutboxIT#状态变更写本地消息`、`OwnerArchiveConstraintIT#动作与原因绑死`【新】、site `OwnerStatusGateIT#停用货主拒绝` | 覆盖（「此后新建被拒」到闸门组件为止，单据域接线见 G-1） |
| R4-S2 | 存量继续：执行中的单据与任务不被停用阻断 | 集成 | site `OwnerStatusDownlinkIT#存量链路不经过闸门` | 覆盖（组件级） |
| R4-S3 | 停用不自动恢复：重启、迁移重跑、定时任务后仍停用 | 集成 + DB 约束 | `OwnerStatusNotAutoRestoredIT#迁移重跑不恢复可用`【新】、`OwnerStatusNotAutoRestoredIT#定时任务不恢复可用`【新】、`OwnerArchiveConstraintIT#只放行两条状态迁移`【新】 | 覆盖 |

### R5 重新启用必须由人操作

| 编号 | 场景 | 覆盖类型 | 现有用例 | 状态 |
|---|---|---|---|---|
| R5-S1 | 启用后回可用、操作人／时间／原因留痕、编号不变；无系统自动恢复路径 | 集成 + 单测 + DB 约束 | `OwnerArchiveApiIT#停用与启用`、`OwnerStatusNotAutoRestoredIT#只有人能恢复可用`【新】、`OwnerArchiveTest#启用`、`OwnerArchiveConstraintIT#只放行两条状态迁移`【新】 | 覆盖 |

### R6 货主选择器只暴露可用货主

| 编号 | 场景 | 覆盖类型 | 现有用例 | 状态 |
|---|---|---|---|---|
| R6-S1 | 条目来自货主档案、字段为编号与展示名、停用档不出现 | 集成 | `OwnerSelectorApiIT#只返回可用货主`、`OwnerSelectorApiIT#停用后不返回`、`OwnerSelectorApiIT#编号精确与名称模糊`、`OwnerSelectorApiIT#只读账号可查`、`OwnerSelectorApiIT#指定列表只看域内` | 覆盖 |
| R6-S2 | 无可用货主时返回空数组，不伪造条目 | 集成 | `OwnerSelectorApiIT#空数组` | 覆盖 |

### R7 货主端账号必须绑定存在的货主

| 编号 | 场景 | 覆盖类型 | 现有用例 | 状态 |
|---|---|---|---|---|
| R7-S1 | 开通货主端账号成功，归属货主即所选档案 | 集成 | `AccountOwnerBindingIT#响应带端与货主字段`、`AccountOwnerBindingIT#开通校验货主绑定` | 覆盖 |
| R7-S2 | 绑定不可改 | 集成 + DB 约束 | `AccountOwnerBindingIT#绑定不可改`、`AccountOwnerBindingIT#库层拒绝改绑定` | 覆盖 |
| R7-S3 | 货主停用后拒绝登录；已建会话下一请求被拒；数据保留 | 集成 | `AccountOwnerBindingIT#停用后登录与令牌失效` | 覆盖 |

### R8 读写权限按角色与数据域双重校验

| 编号 | 场景 | 覆盖类型 | 现有用例 | 状态 |
|---|---|---|---|---|
| R8-S1 | 无写权限账号提交建档／改档／停用／启用被拒（无权限类错误码） | 集成 | `OwnerArchiveDataScopeIT#缺功能权限`（覆盖读与建档）、`OwnerPermissionBootstrapIT#角色绑定`（写权限只授主数据管理员） | 部分（改档／停用／启用的 403 未逐条打，见第 5 节 G-6） |
| R8-S2 | 货主端用户不可见管理入口、直接访问接口同样被拒 | 集成 | `OwnerArchiveDataScopeIT#货主端令牌全拒`（API-020 至 API-027 逐一 403）、archtest `UnitIsolationRulesTest` | 覆盖（菜单侧为前端，见第 5 节 G-7） |

---

## 2 红线与不变量矩阵（INV-06、INV-18、INV-19）

| 编号 | 不变量 | 层次 | 测试类 | 状态 |
|---|---|---|---|---|
| INV-06 | 货主是唯一隔离维度，不可绕过（仓储统一注入，禁止手写） | 应用校验 + 集成（四处收窄） | `OwnerArchiveDataScopeIT#指定列表可见域内`（列表 + 详情）、`#域外写目标按不存在处理`【新】（写接口 + 受理查询）、`#受理查询按域收窄`【新】（受理查询）、`#空域拒绝`、`#全域可见`、`#未配置域按全部处理`、`OwnerSelectorApiIT#指定列表只看域内`、`OwnerCommandServiceTest#空域拒绝且不查询`／`#域外按不存在`、`OwnerScopeTest` | 覆盖 |
| INV-18 | 数据不物理删除 | DB 约束 + 构建期 | `OwnerArchiveConstraintIT#不物理删除`【新】（仓储无删除方法 + 迁移脚本无删除语句 + 删除未授予 PUBLIC）、`OwnerStatusOutboxIT`（停用保留行与留痕） | 部分（库层 `revoke` 对表属主无效，见 F-6） |
| INV-19 | 权限双重校验：功能权限 + 数据域 | 应用校验 + 集成 | `OwnerArchiveDataScopeIT`（8 条含两处【新】）、`OwnerPermissionBootstrapIT`（3 条）、`OwnerArchiveObservabilityIT#越权指标可观察` | 覆盖 |

### 2.1 后端设计不变量（OI-01 至 OI-13）与落点

| 编号 | 落点 | 状态 |
|---|---|---|
| OI-01 编号唯一不可变 | kernel `OwnerNumberRuleTest`；DB `OwnerArchiveConstraintIT#编号唯一`【新】、`#编号与主键不可变`【新】；集成 `OwnerArchiveApiIT#建档主链路` | 覆盖 |
| OI-02 两态闭集 | kernel `OwnerStateMachineTest`；DB `OwnerArchiveConstraintIT#状态与动作闭集`【新】、`#只放行两条状态迁移`【新】、`#合法迁移被放行`【新】、`#动作与原因绑死`【新】 | 覆盖 |
| OI-03 四项必填 | kernel `OwnerFieldsTest`；集成 `OwnerArchiveApiIT#四项必填`；DB `OwnerArchiveConstraintIT#四项必填不可为空`【新】、`#字段形态由库层兜住`【新】 | 覆盖 |
| OI-04 信用代码唯一（含停用档占位） | 集成 `OwnerArchiveApiIT#信用代码重复`；DB `OwnerArchiveConstraintIT#信用代码唯一且停用档占位`【新】 | 覆盖 |
| OI-05 名称可重复 | 集成 `OwnerArchiveApiIT#同名不阻断`【新】；DB `OwnerArchiveConstraintIT#名称可重复`【新】（反证：无含 owner_name 的唯一索引） | 覆盖 |
| OI-06 不物理删除 | 见 INV-18 | 部分 |
| OI-07 双重校验 | 见 INV-19 | 覆盖 |
| OI-08 空域拒绝、不回落成全部 | 单测 `OwnerCommandServiceTest#空域拒绝且不查询`、`OwnerScopeTest`；集成 `OwnerArchiveDataScopeIT#空域拒绝` | 覆盖 |
| OI-09 版本递增 | kernel `OwnerVersionRuleTest`；集成 `OwnerArchiveApiIT#改档与版本冲突`、`OwnerArchiveConflictIT#版本来回版本单调递增`【新】／`#不同键并发只有一笔成功`【新】；DB `OwnerArchiveConstraintIT#版本逐次递增`【新】 | 覆盖 |
| OI-10 绑定不可变 | 集成 `AccountOwnerBindingIT#绑定不可改`、`#库层拒绝改绑定` | 覆盖 |
| OI-11 停用阻断新建、不阻断存量 | site `OwnerStatusGateIT`（5 条）、`OwnerStatusGateDisabledIT`（1 条）、`OwnerStatusGateDependencyTest`【新】（3 条）、`OwnerStatusDownlinkIT#存量链路不经过闸门` | 覆盖（组件级；单据域接线见 G-1） |
| OI-12 不自动恢复 | 集成 `OwnerStatusNotAutoRestoredIT`【新】（3 条）；DB `OwnerArchiveConstraintIT#只放行两条状态迁移`【新】 | 覆盖 |
| OI-13 选择器只读可用 | `OwnerSelectorApiIT`（9 条）、`OwnerSelectorServiceTest`、`OwnerSelectorViewTest` | 覆盖 |

---

## 3 契约验收（`design.md` 1.11）判定项

| 1.11 判定项 | 覆盖类型 | 落点 | 状态 |
|---|---|---|---|
| 完整性：8 个条目均有路径／方法／请求／响应／错误码／鉴权／分页／幂等 | 构建期 | `OwnerContractDocumentTest#路径与方法`【新】（8 组路径×方法）、`#幂等键落在写接口上`【新】、`#端分组不混用`【新】 | 部分（产物缺 202 与错误码响应，见 F-1、F-2） |
| 必填四项逐项缺一 → 400 + `details[].field` | 集成 | `OwnerArchiveApiIT#四项必填` | 覆盖 |
| 编号规则：形态、改档／停用／启用不变、并发两笔不同编号 | 集成 + 并发 | `OwnerArchiveApiIT#建档主链路`、`OwnerArchiveConflictIT#版本来回版本单调递增`【新】、`OwnerArchiveConcurrencyIT#同键并发建档` | 覆盖（「两笔并发建档得两个不同编号」未单独打，见 G-4） |
| 名称可重复 | 集成 + DB | `OwnerArchiveApiIT#同名不阻断`【新】、`OwnerArchiveConstraintIT#名称可重复`【新】 | 覆盖 |
| 信用代码唯一：422、十次并发同码只一条、小写先 400 | 集成 + DB | `OwnerArchiveApiIT#信用代码重复`、`OwnerArchiveConstraintIT#信用代码唯一且停用档占位`【新】、kernel `OwnerCreditCodeRuleTest` | 部分（「十次并发同码」未单独打，见 G-5） |
| 两态且不代表合同、响应无合同字段 | 构建期 + 集成 | `OwnerContractDocumentTest#状态枚举闭集`【新】／`#不含合同字段`【新】、`OwnerArchiveApiIT#建档主链路` | 覆盖 |
| 停用边界：选择器不返回、原因可查、作业域拒绝 | 集成 | `OwnerSelectorApiIT#停用后不返回`、`OwnerArchiveApiIT#停用与启用`、site 闸门 4 条 | 部分（作业域用例属单据域，见 G-1） |
| 停用不自动恢复 | 集成 | `OwnerStatusNotAutoRestoredIT`【新】 | 覆盖 |
| 重新启用：仅有写权限者可启用、留痕含原因与操作人、无写权限 403 | 集成 | `OwnerArchiveApiIT#停用与启用`、`OwnerStatusNotAutoRestoredIT#只有人能恢复可用`【新】 | 部分（无写权限账号启用未单独打，见 G-6） |
| 选择器数据来源：条目来自档案、停用不出现、无档案空数组、不派生自账号 | 集成 | `OwnerSelectorApiIT#只返回可用货主`（造数不经账号）、`#空数组` | 覆盖 |
| 选择器字段：`ownerNo` 精确、`ownerName` 模糊、`ownerCode` 被拒 | 集成 | `OwnerSelectorApiIT#编号精确与名称模糊`、`#旧参数被拒` | 覆盖 |
| 账号绑定约束：未选／不存在 → 400、改 `ownerId` → 400 | 集成 | `AccountOwnerBindingIT#开通校验货主绑定`、`#绑定不可改` | 覆盖 |
| 停用货主登录被拒与会话失效 | 集成 | `AccountOwnerBindingIT#停用后登录与令牌失效` | 覆盖 |
| 货主端越权：API-020 至 API-027 全 403 | 集成 | `OwnerArchiveDataScopeIT#货主端令牌全拒` | 覆盖 |
| 无权限：403 `AUTHZ_2001`、不返回空数据 | 集成 | `OwnerArchiveDataScopeIT#缺功能权限` | 部分（4 个写接口未逐条打，见 G-6） |
| 数据域：列表／详情／写／域空／非全域建档 | 集成 | `OwnerArchiveDataScopeIT`（8 条，含两处【新】） | 覆盖 |
| 幂等：十次同键并发、同键异体、处理中 | 并发 + 集成 | `OwnerArchiveConcurrencyIT`（2 条）、`OwnerArchiveApiIT#同键重复提交`／`#同键异体`、`OwnerArchiveConflictIT#同键异体覆盖路径参数`【新】／`#处理中返回可重试`【新】 | 覆盖 |
| 幂等过期：24 小时窗口后按新请求处理 | 集成 | 无 | 未覆盖（见 G-2） |
| 错误码：1.4 表每行至少一个触发用例、四字段齐全、`message` 不含堆栈 | 单测 + 集成 + 构建期 | `OwnerHttpTest`【新】（取值 + 状态 + retryable 逐行）、`OwnerArchiveApiIT#缺幂等键`、`OwnerArchiveRateLimitIT`、`OwnerArchiveObservabilityIT` | 覆盖（`message` 正则断言分散在各 IT 的响应体断言里，见 G-8） |
| 超时与重试、限流隔离、降级、资源边界 | 集成 | `OwnerArchiveRateLimitIT`（3 条）、`OwnerArchiveQueryIT#参数边界`、`OwnerArchiveApiIT#受理超时兜底` | 部分（`limit=101`／`cursor` 超 512／请求体 16 KB+1 只到 `limit`，见 G-9） |
| 契约一致：`openapi.json` 与本章逐项一致 | 构建期 | `OwnerContractDocumentTest`【新】（16 条） | **部分**（产物 5 处不一致，见 F-1 至 F-5） |

---

## 4 覆盖类型分布（本次交付后）

| 层次 | 货主档案相关测试类 | 用例数 | 说明 |
|---|---|---|---|
| 单测（kernel） | 6 | 68 | `OwnerStateMachineTest` 13、`OwnerFieldsTest` 15、`OwnerCreditCodeRuleTest` 13、`OwnerNumberRuleTest` 13、`OwnerVersionRuleTest` 5、`OwnerListSortTest` 9 |
| 单测（hub 应用／契约） | 3 | 39 | `OwnerCommandServiceTest` 10 + `OwnerHttpTest`【新】13 + `OwnerContractDocumentTest`【新】16 |
| 单测（site，货主相关） | 2 | 5 | `OwnerStatusGateDependencyTest`【新】3 + `RabbitOwnerStatusListenerTest` 2（site 单测模块合计 24） |
| DB 约束（真库） | 1 | 24 | `OwnerArchiveConstraintIT`【新】（13 方法，含两组参数化） |
| 集成（hub，真 HTTP + 真库） | 13 | 见模块合计 149 | `OwnerArchiveApiIT`／`Query`／`DataScope`／`Concurrency`／`Conflict`【新】／`Observability`／`RateLimit`／`WindowOne`／`StatusOutbox`／`StatusNotAutoRestored`【新】／`OwnerSelectorApiIT`／`OwnerPermissionBootstrapIT`／`OwnerArchiveConstraintIT`【新】，外加身份侧 `AccountOwnerBindingIT` |
| 集成（site） | 4 | 38（模块合计） | 闸门 2 + 下行 1 + 存量链路（含在 `OwnerStatusDownlinkIT`） |
| 架构（ArchUnit） | 3 | 26 | AR-11 双向依赖 + 单仓端不引用 Hub 货主包 |

模块汇总（`mvn clean verify`，本次交付后）：kernel 122、hub 单测 148、hub 集成 149、site 单测 24、site 集成 38、架构 26，全部通过。

---

## 5 缺口与未覆盖项（含原因，不用假断言凑数）

### 5.1 生产缺陷（需 `backend-impl` 修，本次测试不改生产代码）

| 编号 | 缺陷 | 位置 | 期望行为 | 归属 |
|---|---|---|---|---|
| F-1 | 四个写接口在契约产物里声明 `200` 与「只有 200」，契约要求 `202` 且声明错误码 | `code/contracts/openapi.json` 的 `/api/hub/owner-archives`（post）、`/{ownerNo}`（put）、`/{ownerNo}/disable`、`/{ownerNo}/enable` | 四个写操作声明 `202`（受理票据）并逐条声明 1.4 表的错误码；修改点在控制器补 `@ApiResponse`，再重导产物（`ContractExportTool`） | 后端 |
| F-2 | 货主档案 8 个操作在产物里没有任何错误码响应；对比商品档案 `/api/hub/archives` 已声明 202/400/404/409/422/429/500/503 | 同上 + `/api/hub/owners`、`/acceptances/{acceptNo}` | 按 1.4 表与 1.8 条目逐条声明 | 后端 |
| F-3 | 建档与改档的请求体在产物里指向**别人的** schema：`POST /api/hub/owner-archives` 的 `requestBody` 是身份模块账号开通的 `CreateRequest`（`accountName`／`initialPassword`／`client`／`ownerId`／`roleIds`），`PUT /{ownerNo}` 是商品档案的 `EditRequest`（`name`／`ownerCode`／`barcode`…）。根因是 `wms-hub` 里有五个同名 `CreateRequest` 记录，产物按简单名归并只有一个 | `code/contracts/openapi.json` 的 `components.schemas.CreateRequest`／`EditRequest` | 货主档案的请求体必须是四项 + `version`；schema 需改名避让（如 `OwnerCreateRequest`／`OwnerEditRequest`），重导后核对 | 后端 |
| F-4 | `lastStatusChange.action` 的枚举多出 `EDIT`（`[CREATE, EDIT, DISABLE, ENABLE]`），契约 1.5 与 `21` E-19 只有三个取值 | `components.schemas.OwnerStatusChangeView.action.enum`（源头为 `OwnerArchiveViews` 直接引用 kernel 的 `OwnerAction`） | 对外枚举收窄到 `CREATE`／`DISABLE`／`ENABLE`；`EDIT` 不对外出现（改档不写状态留痕） | 后端 |
| F-5 | API-020 的旧参数 `ownerCode`／`name` 仍以 `deprecated: true` 出现在产物里；契约 1.11 要求「旧参数不再出现在生成物中」。同时语言表述与 API-020 的「`details[].field` 给出旧名与新名的对应」冲突——不绑定参数就无法给出字段级错误 | `components` 的 `/api/hub/owners` 两个 query 参数 | 两条口径需设计方先统一：要么「收进过滤器手工解析」使产物干净，要么 1.11 改为「允许以 deprecated 形式出现」。**本项首先是设计章内部冲突** | 设计 + 后端 |
| F-6 | INV-18 的库层手段在当前部署口径下不生效：迁移以 `wms` 账号执行、`wms` 即表属主，而脚本只 `revoke delete, truncate on wms_owner from public`——属主权限不受 `revoke ... from public` 影响。实证：集成测试用例结束时的 `delete from wms_owner where id = ?` 全部成功 | `code/db/migration/hub/V20261136__hub_owner_archive.sql` 最后一段 | 需要真正的手段：改用独立的业务角色（非属主）+ `grant select, insert, update`，或对属主显式 `revoke`，或在 `11` 登记「INV-18 在货主档案上由仓储接口 + 构建期扫描兜底，库层手段仅约束非属主角色」 | 后端 + 设计 |
| F-7 | API-020 的成功响应 schema（`ArchiveResponseOwnerSelectorPage`）声明了 `dataTimestamp`，而 API-020「响应」行与契约 1.1「查询元信息」要求正常查询不带该字段 | `components.schemas.ArchiveResponseOwnerSelectorPage.properties.dataTimestamp` | 选择器改用不带 `dataTimestamp` 的信封（`OwnerResponse`），或该字段不进成功响应 schema | 后端 |

### 5.2 未覆盖项（无测试手段或不在本变更交付面）

| 编号 | 项 | 原因 | 需谁定 |
|---|---|---|---|
| G-1 | 「以停用货主为对象的新建单据被拒」「存量单据走到终态」的**单据域**用例 | 本变更只交付 `OwnerStatusGate` 组件与副本，接线到具体单据由单据域变更完成；错误码也归单据域契约（design「本变更只交付闸门」）。已覆盖到组件级（`OwnerStatusGateIT` 4 条 + `OwnerStatusGateDisabledIT`），未覆盖到单据受理 | 单据域契约负责人 |
| G-2 | 幂等 24 小时窗口过期后同键重提按新请求处理（建档应 422） | 窗口 `expiresAt` 由 `IdempotencyService` 在受理时写入 24 小时，测试无法在不改生产代码的前提下把窗口推到过期；`hub_idempotency.expires_at` 可改，但那等于把「窗口逻辑」的验证变成「改库造假」（`IdempotencyDecisionTest` 已用纯函数覆盖「过期即 PROCEED」的判定分支）。**建议**：给 `IdempotencyService` 注入可替换的 `Clock`，由主代理决定是否立项 | 后端 |
| G-3 | `wms_account.owner_id` 外键的**行为**断言（插非法货主标识被拒） | 第二窗口运维脚本未执行，库中不存在该外键。已在 `OwnerArchiveConstraintIT#归属货主外键仍在第二窗口`【新】按「窗口未执行」断言：约束不存在 + 定义只留在 `code/db/ops/V20261138…sql` + 自动迁移目录里不得出现它；窗口执行后同一用例自动切到「约束为 restrict/restrict」分支，不产生假红 | 发布负责人（执行窗口） |
| G-4 | 两笔并发建档得到两个不同编号 | 需要两个不同幂等键的并发建档；`OwnerArchiveConcurrencyIT` 走的是同键并发（只产生一个受理号）。加一条需要额外一组真库写入，收益低于成本，且编号唯一性已由 `uk_wms_owner_no` 与 `OwnerArchiveConstraintIT#编号唯一` 兜住 | 测试设计（本代理判定不做） |
| G-5 | 十次并发同码建档只有一条成功 | 同 G-4：`creditCode` 唯一性已由 `uk_wms_owner_credit_code`（`OwnerArchiveConstraintIT#信用代码唯一且停用档占位`）与串行重复（`OwnerArchiveApiIT#信用代码重复`）兜住；并发形态的收益是验证「后到者拿到 422 而不是 500」，需要额外的受理轮询等待 | 测试设计（本代理判定不做） |
| G-6 | 改档／停用／启用三个写接口在「无 `owner.hub.write`」下的 403 逐条断言 | 现只打了读接口与建档（`OwnerArchiveDataScopeIT#缺功能权限`）。三条路径的权限点与建档同点、同一拦截器，逐条重复断言的边际判定力低；若要补齐，需为每条路径各造一个无权限账号 | 测试设计（本代理判定不做，若流程要求逐条可另立任务） |
| G-7 | 货主端菜单不出现管理入口 | 前端 `code/web` 目前**无测试框架**（无 Vitest／Playwright），本变更不擅自引入；服务端侧的「直接访问接口被拒」已覆盖 | 前端负责人（测试栈缺口，见第 6 节） |
| G-8 | `message` 不含堆栈／SQL／表名／内部地址的正则断言 | 现有 IT 对响应体做精确键断言与错误码断言，但未对 `message` 做 `at `/`select`/`http://` 的正则排查（契约 1.2「统一错误体」判定标准）。补它需要一条遍历所有错误响应的用例，本批未做 | 测试设计（可另立任务） |
| G-9 | 资源边界：请求体 16 KB + 1 字节、`cursor` 超 512 字符、四项各超长 1 字符 | 现只覆盖 `limit=0/101`、`sortBy`／`sortDir`／`status` 白名单外。请求体上限与 `cursor` 长度上限的实现存在（`OwnerEntryChecks`），但逐条边界未打 | 测试设计（可另立任务） |
| G-10 | 安卓端 | `code/mobile` 工程尚未建立，无测试可写 | 移动作业端负责人 |

---

## 6 本次落盘清单

| 文件 | 类型 | 用例数 | 覆盖的不变量 |
|---|---|---|---|
| `wms-hub/src/test/java/com/huoling/wms/masterdata/owner/infrastructure/OwnerArchiveConstraintIT.java` | 新增，DB 约束（真库） | 13 方法 / 24 执行 | OI-01～OI-05、OI-09、OI-12、INV-18；第二窗口外键口径 |
| `wms-hub/src/test/java/com/huoling/wms/masterdata/owner/api/OwnerArchiveConflictIT.java` | 新增，并发 + 集成 | 4 | 契约 1.2「并发」、1.3「幂等」：不同键并发、版本来回、同键异体（路径参数）、处理中 |
| `wms-hub/src/test/java/com/huoling/wms/masterdata/owner/api/OwnerHttpTest.java` | 新增，单测 | 13 | 契约 1.4 错误码表逐行（取值 + HTTP + retryable） |
| `wms-hub/src/test/java/com/huoling/wms/hub/OwnerContractDocumentTest.java` | 新增，构建期契约对账 | 16 | 契约 1.11「契约一致」中当前一致的部分（路径／方法／幂等键／字段名／枚举／无计费字段／details 结构） |
| `wms-hub/src/test/java/com/huoling/wms/masterdata/owner/application/OwnerStatusNotAutoRestoredIT.java` | 新增，集成 | 3 | OI-12：迁移重跑、定时任务、唯一人工启用入口 |
| `wms-site/src/test/java/com/huoling/wms/site/application/OwnerStatusGateDependencyTest.java` | 新增，单测 | 3 | 降级分支「Hub 不可达且副本在阈值内放行」：闸门不依赖 Hub + 阈值内外两条判定 |
| `wms-hub/src/test/java/com/huoling/wms/masterdata/owner/api/OwnerArchiveDataScopeIT.java` | 追加 | +2 | INV-06 的第四处（受理查询）与写接口域外 |
| `wms-hub/src/test/java/com/huoling/wms/masterdata/owner/api/OwnerArchiveApiIT.java` | 追加 | +1 | R3-S3 同名不阻断（接口层） |

合计新增/追加 **66 次用例执行**（24+4+13+16+3+3+2+1），无 `@Disabled`、无吞异常、无放宽断言。

### 测试栈缺口（需他人定）

| 缺口 | 现状 | 需谁定 |
|---|---|---|
| 前端 `code/web` 无测试框架 | 无 Vitest／Playwright；本变更不引入 | 前端负责人 |
| 安卓 `code/mobile` 工程未建立 | 无工程、无测试 | 移动作业端负责人 |
| `*IT` 构建配置 | 已有：父 POM 的 failsafe 在 `verify` 阶段跑 `*IT`，未加 `skipITs`、未包 profile；集成测试依赖本地 PostgreSQL／Redis／RabbitMQ | 无需变更 |

---

## 7 「能红」验证说明（如实记录）

- **实跑观测到的红**：`OwnerArchiveConstraintIT#状态与动作闭集` 首版把「闭集 CHECK」写成「改状态到 `PENDING`」，实际先被状态守卫触发器拦下（报错文本是「货主状态只允许…」而非常量名），用例当场变红。这条失败证明：(1) 该类的断言确实绑在真库的强制手段上，不是自说自话；(2) 状态守卫与闭集 CHECK 是**两条**独立手段，必须各用一条用例（已按此拆分：改状态用触发器、闭集用 INSERT）。
- **未实跑的变异验证**：其余用例的「能红」由单点变异命名给出（下节），但**没有实际执行**这些变异——执行它们要改生产代码或改库结构（`alter table … drop constraint`），两者都在本次执行边界之外。需要主代理决定是否另开一轮变异验证。

| 用例 | 单点变异（改这一处即红） |
|---|---|
| `OwnerArchiveConstraintIT#四项必填不可为空` | 去掉 `V20261136` 里任一列的 `not null` |
| `#字段形态由库层兜住` | 放宽 `ck_wms_owner_credit_code` 的字符集（例如允许小写） |
| `#状态与动作闭集` | 把 `ck_wms_owner_status` 的枚举加一个 `PENDING` |
| `#动作与原因绑死` | 把 `ck_wms_owner_reason` 改成恒真 |
| `#编号唯一` / `#信用代码唯一且停用档占位` | 删掉 `uk_wms_owner_no` / 把 `uk_wms_owner_credit_code` 改成带 `where status='ACTIVE'` 的部分索引 |
| `#名称可重复` | 给 `wms_owner` 加一条 `unique (owner_name)` |
| `#编号与主键不可变` | 删掉 `wms_owner_guard()` 的第一段判断 |
| `#只放行两条状态迁移` / `#合法迁移被放行` | 把触发器里的两条合法迁移条件改成另一种组合（例如去掉 `status_action` 判定） |
| `#版本逐次递增` | 把 `new.version <> old.version + 1` 改成 `new.version <= old.version` |
| `#不物理删除` | 给 `OwnerArchiveRepository` 加 `deleteByOwnerNo`，或往迁移目录放一条 `delete from wms_owner` |
| `#归属货主外键仍在第二窗口` | 把 `fk_wms_account_owner` 的 `add constraint` 搬进迁移目录，或删掉运维脚本里的外键定义 |
| `OwnerArchiveConflictIT#不同键并发只有一笔成功` | 把 `update ... where version = ?` 去掉 `version` 条件（两笔都成功） |
| `#版本来回版本单调递增` | 让 `OwnerVersionRule.next` 返回原值（版本不前进） |
| `#同键异体覆盖路径参数` | 指纹改为只算请求体（不含 `requestTarget`，换货主也回放） |
| `#处理中返回可重试` | 把 `IdempotencyDecision` 的 `IN_PROGRESS → REPLAY`（处理中回放受理号） |
| `OwnerHttpTest` | 把 `CONFLICT_VERSION` 的 `retryable` 改成 `false`，或把 `STATE_INVALID` 映射到 400 |
| `OwnerContractDocumentTest` 各条 | 改控制器路径名、删掉写接口的 `Idempotency-Key` 头、把 `ownerId` 改成数字类型、给 schema 加计费字段 |
| `OwnerStatusNotAutoRestoredIT#迁移重跑不恢复可用` | 加一条 `update wms_owner set status='ACTIVE'` 的迁移脚本 |
| `OwnerStatusGateDependencyTest#闸门不依赖Hub` | 往 `OwnerStatusGate` 注入一个 `HubUplinkClient` 做同步查询 |
| `OwnerArchiveDataScopeIT#受理查询按域收窄` | 把 `OwnerAcceptanceQueries.of` 的 `subjectOwnerIds` 恒传 `null`（受理查询不收窄） |
| `OwnerArchiveApiIT#同名不阻断` | 在建档入口加名称唯一性校验 |
