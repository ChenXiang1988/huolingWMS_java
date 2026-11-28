# Design

## Context

本次变更的需求源头是 `proposal.md` 与 `specs/` 下三份需求差异；当前有效版本的 `26-商品档案.md` 定义了字段分层与货主内判重，`05-角色与权限矩阵.md` 与 `24-分端功能清单.md` 对货主写权限存在冲突，`22-状态机与事务边界总表.md` 只到三态。代码中 `wms-hub` 目前只有身份与权限模块，`code/contracts/openapi.json` 没有商品档案接口，`code/web/` 只有运营端外壳。

## Goals / Non-Goals

**Goals:**

- 把货主创建编辑停用、运营全量管理与审核、单仓只读与变更申请落成一套可实现的三端能力。
- 把审核态、权限口径与单仓变更边界同步回当前有效版本的设计文档。
- 用版本号与下行摘要保证三端看到的一致性，单仓不直接改主数据。

**Non-Goals:**

- 不设计移动作业端原生页面，不维护库位关联，库位与库存关系仍归库存模型。
- 不实现开放接口入口，不引入物理删除，不处理跨境关务与物流段。
- 不改动 `00-决策字典.md` 与 `00a-红线与不变量.md` 的既有结论。

## Decisions

| 决策 | 选择 | 理由与备选 |
|---|---|---|
| 审核态落法 | 保留草稿、启用、停用三态，新增待审核与驳回；审核通过是进入启用的动作 | 备选是把「审核通过」也做成独立持久态，但与启用语义重复，会增加端间映射成本 |
| 货主写权 | 正式放开创建、编辑、提交与停用；启用态字段变更仍需审核 | 备选是维持首期只读，与本次需求和 `26` 已定三入口冲突 |
| 删除语义 | 逻辑停用，保留档案与历史流水 | 符合 INV-18；物理删除被否决 |
| 单仓维护 | 只发起主数据字段变更申请，由运营审核后生效 | 备选是直接改本仓全字段，会破坏权威源唯一与端间一致性 |
| 权威源 | 商品档案以 Hub 侧为准，Site 只保存生效档案缓存 | 与 D12 的主数据唯一权威源口径一致 |

## 前端设计

### 页面清单与路由

| 端 | 路由 | 页面职责 | 主要角色 | 新增或改造 | 依赖接口编号 |
|---|---|---|---|---|---|
| 货主端 | `/owner/archives` | 查询与维护自有商品档案 | 货主用户 | 新增 | API-001、API-002、API-003、API-004、API-005 |
| 运营端 | `/hub/archives` | 全量档案查询与代建 | 主数据管理员 | 改造 | API-006、API-007 |
| 运营端 | `/hub/archives/review` | 待审核档案与驳回处理 | 主数据管理员 | 新增 | API-008、API-009 |
| 运营端 | `/hub/archive-change-requests` | 单仓变更申请审核 | 主数据管理员 | 新增 | API-012、API-013 |
| 单仓端 | `/site/archives` | 本仓生效档案查询 | 仓管员 | 新增 | API-010 |
| 单仓端 | `/site/archive-change-requests` | 变更申请发起与跟踪 | 仓管员 | 新增 | API-011、API-014 |

写入交互统一按 D20 设计：提交后先展示受理号与处理中状态，按所属端调用 API-015、API-016 或 API-017 每 3 秒轮询，最长 60 秒，结果由受理查询返回，不设计成提交即生效。单仓端缓存查询展示数据时间戳与待刷新提示，不把缓存数据伪装为权威结果；单仓端会话按 API-018、API-019 获取与续期。

### 页面与接口映射表

| 页面 | 触发时机 | 接口编号 | 用途 | 失败表现 |
|---|---|---|---|---|
| 货主端档案列表 | 进入页面与筛选 | API-005 | 查询自有档案 | 显示加载态；失败时保留筛选条件并给重试入口 |
| 货主端新建表单 | 点击新建 | API-001 | 创建草稿 | 字段错误定位到输入框；失败可重试 |
| 货主端编辑表单 | 草稿或驳回态点击编辑 | API-003 | 保存修改 | 版本冲突提示刷新后再编辑 |
| 货主端提交按钮 | 草稿或驳回态提交 | API-002 | 提交审核 | 重复提交提示已受理；状态冲突跳回列表 |
| 货主端删除按钮 | 启用态点击删除 | API-004 | 逻辑停用 | 二次确认后提交；失败保持原状态 |
| 运营端全量列表 | 进入页面与筛选 | API-007 | 全量查询 | 无权限时隐藏入口；失败给重试 |
| 运营端代建表单 | 点击代建并指定货主 | API-006 | 代建草稿 | 归属未指定则禁止提交 |
| 运营端审核列表 | 进入待审核队列 | API-008 | 单条审核 | 驳回原因为空时禁止提交 |
| 运营端批量操作 | 勾选多条并执行 | API-009 | 批量通过、驳回或停用 | 逐条展示结果与失败原因 |
| 运营端申请审核页 | 进入申请队列 | API-012、API-013 | 查询并审核变更申请 | 状态已变化时提示刷新 |
| 单仓端档案列表 | 进入页面与筛选 | API-010 | 查询本仓生效档案 | 降级时显示数据时间戳与待刷新 |
| 单仓端申请表单 | 点击发起变更 | API-011 | 提交字段变更申请 | 非本仓档案禁止选择 |
| 单仓端申请跟踪 | 查看申请状态 | API-014 | 查询申请进度 | 加载失败给重试，空态说明尚无申请 |
| 三端受理结果轮询 | 写入提交后每 3 秒 | API-015、API-016、API-017 | 查询受理状态并替换结果 | 超时保留受理号并给刷新出口 |
| 单仓端会话获取与续期 | 进入应用或令牌即将过期 | API-018、API-019 | 获取站点令牌、权限与仓标识 | 凭证错误或端标识错误按无权限处理 |
| 运营端代建归属选择 | 打开代建表单 | API-020 | 搜索并选择归属货主 | 无结果给空态，失败给重试 |

### 菜单与权限点

| 菜单或按钮 | 角色 | 权限点 | 未命中表现 | 备注 |
|---|---|---|---|---|
| 货主端商品档案菜单 | 货主用户 | `archive.owner.read` | 隐藏菜单 | 待并入权限矩阵 |
| 货主端新建、编辑、提交、删除按钮 | 货主用户 | `archive.owner.write` | 按钮禁用并说明原因 | 待并入权限矩阵 |
| 运营端档案菜单 | 主数据管理员 | `archive.hub.read` | 隐藏菜单 | 待并入权限矩阵 |
| 运营端代建按钮 | 主数据管理员 | `archive.hub.create` | 按钮禁用 | 待并入权限矩阵 |
| 运营端审核与批量按钮 | 主数据管理员 | `archive.hub.review` | 按钮禁用 | 待并入权限矩阵 |
| 单仓端档案菜单 | 仓管员 | `archive.site.read` | 隐藏菜单 | 待并入权限矩阵 |
| 单仓端发起变更按钮 | 仓管员 | `archive.site.change` | 按钮禁用 | 待并入权限矩阵 |

前端只隐藏入口与展示服务端错误，不判断业务结果；功能权限与数据域权限以服务端校验为准。货主端页面不展示单仓部署信息或库位号。

### 组件装配

复用现有列表页、表单页与详情抽屉骨架：列表页用统一筛选区与键集分页组件；表单页复用字段分组与校验提示组件；详情沿用统一右侧抽屉骨架，由列表行打开，不新增独立详情路由。单仓端申请表单复用同一表单骨架，但字段集合限定为允许变更的主数据字段。

### 状态与数据归属

| 状态 | 放哪 | 理由 |
|---|---|---|
| 查询条件、分页游标、选中行 | 页面地址参数 | 可分享、可回退 |
| 跨页选择的待审核条目 | 页面内状态管理 | 仅在审核页内共享 |
| 服务端档案数据 | 页面按需请求，提交或审核后失效 | 不做全量本地缓存 |
| 单仓缓存数据 | 服务端缓存，前端只接收摘要 | 前端不自行缓存主数据 |

### 表单与校验

前端只做必填、长度、格式与枚举闭集的格式校验；货主内编码判重、状态迁移、变更申请是否允许由服务端返回。提交时携带客户端请求号作为幂等键，提交中按钮禁用并展示受理号。错误码按字段或整页提示，文案层级引用现有反馈规范。

### 异常与空态

加载中、空数据、无权限、接口失败、降级数据五类在三端列表页与审核页分别出现。降级数据必须显示数据时间戳；无权限态隐藏入口而不是展示空数据；接口失败保留当前筛选与表单内容并提供重试入口；状态冲突提示刷新后重新加载。

### 边界说明

不设计移动作业端原生页面、后端实现、接口定义、交互动线细节与视觉规格；本段只落页面、路由、接口引用、权限点、状态归属与异常落点。

## 后端设计

### 模块与依赖

改动落在 Hub 的商品档案聚合、审核聚合与认证入口，Site 单元承载 `/api/site/**`，增加只读缓存、变更申请发起与下行消费能力；认证由 Hub 签发 `SITE` 令牌并下行权限副本。Kernel 定义状态迁移、判重与版本规则，Hub 与 Site 只调用，不各写一套校验。依赖方向保持 Hub、Site 单向依赖 Kernel 与公共模块，不产生反向依赖，ArchUnit 断言沿用现有规则。

| 接口编号 | 后端落点 |
|---|---|
| API-001 | Hub 商品档案应用服务创建草稿 |
| API-002 | Hub 商品档案应用服务提交判重 |
| API-003 | Hub 商品档案应用服务编辑草稿 |
| API-004 | Hub 商品档案应用服务逻辑停用 |
| API-005 | Hub 商品档案查询服务按归属货主过滤 |
| API-006 | Hub 商品档案应用服务代建草稿 |
| API-007 | Hub 商品档案查询服务全量视图 |
| API-008 | Hub 审核应用服务单条审核 |
| API-009 | Hub 审核应用服务批量处理 |
| API-010 | Site 单元缓存查询服务按本仓过滤 |
| API-011 | Site 单元变更申请应用服务发起并上行 Hub |
| API-012 | Hub 变更申请查询服务 |
| API-013 | Hub 变更申请审核服务写回档案 |
| API-014 | Site 单元变更申请查询服务 |
| API-015 | Hub 受理记录服务按货主域查询 |
| API-016 | Hub 受理记录服务全量查询 |
| API-017 | Site 单元受理记录服务按本仓查询 |
| API-018 | Hub 认证入口签发站点令牌 |
| API-019 | Hub 认证入口续期站点令牌 |
| API-020 | Hub 货主选择器查询服务 |

### 领域模型与状态机

| 状态 | 含义 | 允许动作 |
|---|---|---|
| 草稿 | 未提交，不进业务链路 | 编辑、提交 |
| 待审核 | 已提交，等待运营审核 | 审核通过、驳回 |
| 审核通过即启用 | 已生效，可被单据与库存引用 | 停用、发起字段变更 |
| 驳回 | 审核未通过，保留原因 | 编辑后重新提交 |
| 停用 | 不接新单，存量可走完 | 提交恢复 |

状态迁移只允许：草稿到待审核；待审核到启用或驳回；驳回回到待审核；启用到停用；停用到待审核。Kernel 状态机守卫拒绝其他迁移。档案归属货主创建后不可变，依据 D02、D03；所有查询强制注入货主条件，依据 INV-06；数据不物理删除，依据 INV-18；写接口同时校验功能权限与数据域，依据 INV-19。

### 强制手段分层

| 规则 | 强制手段 | 依据 |
|---|---|---|
| 归属货主不可变 | 数据库层面仅插入不更新归属列；应用层校验 | INV-06、D02 |
| 货主内编码与条码唯一 | 部分唯一索引，条件排除停用与逻辑删除记录 | INV-06、`26` 判重规则 |
| 数据不物理删除 | 业务表不授予删除权限，设置停用标记 | INV-18 |
| 双重权限校验 | 统一鉴权切面先校验功能权限，仓储层注入数据域 | INV-19、D15 |
| 状态迁移闭合 | Kernel 状态机守卫，数据库状态枚举约束 | `22` 状态登记要求 |
| 端间版本一致 | 版本号递增检查与下行摘要按版本去重 | D19、`26` 字段分层 |

沿用库存与库位基线，不因本次变更改动：`location_id NOT NULL` 且有外键，依据 INV-01；库存唯一键字段为 `owner_id`、`location_id`、`sku_id`、`batch_no`、`stock_status`、`serial_no`，`serial_no` 采用 `NOT NULL DEFAULT ''` 策略，依据 D29；分配约束 `qty_allocated <= qty_on_hand` 由数据库 `CHECK` 约束兜住，依据 INV-08。商品档案不维护库位关联字段。

### 数据模型与 DDL

| 表名 | 用途 | 权威源 | 新增或变更 | 脚本编号 |
|---|---|---|---|---|
| `hub_sku_archive` | 商品档案主表，含归属、编码、名称、规格、分类、单位、状态与版本号 | Hub | 新增 | `V20261128__hub_product_archive.sql` |
| `hub_sku_review` | 审核动作与驳回原因留痕 | Hub | 新增 | `V20261129__hub_product_review.sql` |
| `hub_product_change_request` | 单仓字段变更申请 | Hub | 新增 | `V20261130__hub_product_change_request.sql` |
| `site_sku_snapshot` | 单仓生效档案缓存与数据时间戳 | Site | 新增 | `V20261131__site_sku_snapshot.sql` |
| `hub_archive_acceptance` | 写接口受理记录、受理状态与结果摘要 | Hub | 新增 | `V20261133__hub_archive_acceptance.sql` |

`hub_sku_archive` 的关键约束：归属列非空并建索引；货主系统编码与条码分别建货主内部分唯一索引；状态枚举封闭；版本号非空且每次更新递增；停用标记与唯一索引条件一致，停用后同编码可被新草稿使用；时间列存 UTC。新增非空列无存量数据，无需回填；若上线时出现回填场景，按加可空列、分批回填、加约束三步执行。所有结构变更只新增 Flyway 脚本，不改历史脚本。

### 事务边界与并发

创建草稿、提交审核、审核通过、驳回、停用各为一个本地事务。审核通过需在同事务完成状态变更、版本号递增、审核留痕与本地消息表落库，再异步下行；不引入分布式事务。并发更新以版本号乐观锁控制，冲突返回版本冲突；同一档案的多次审核由状态机守卫保证只生效一次。

### 幂等与异步事件

所有写接口以客户端请求号或外部单号作为幂等键，作用域为归属货主加接口加键，窗口不少于二十四小时。写请求在同事务写入 `hub_archive_acceptance`（`PROCESSING`）与幂等记录，立即返回 `acceptNo`；异步执行完成后更新受理状态与结果摘要，查询接口按 `acceptNo` 返回。重复请求返回首次受理号，同键异体返回冲突，处理中返回可重试错误。

审核通过后写入本地消息表，事件标识为档案系统编号加版本号；投递器按固定间隔扫描待投递消息，经消息中间件发往单仓端，交换与路由键由部署配置提供。消费方按事件标识去重，写入 `site_sku_snapshot` 并更新数据时间戳；下行摘要包含档案编号、版本号、数据时间戳与变更字段摘要。失败进入重试队列并告警，不影响权威档案状态。下行摘要除编号、版本、数据时间戳与变更字段外，还包含 `ownerId`、`ownerCode`、`name`、`barcode`、`baseUnit`、`storageCondition`、`category`、`spec`、`status`，足以重建单仓只读缓存，不含内部实体。站点令牌与权限副本随同一事件下行，Site 单元据此完成数据域与功能权限校验。

站点凭证、仓标识与初始权限来自部署配置 `wms.archive.site-registry`，不新建仓库档案表；Site 到 Hub 的上行使用 Hub 侧 API-011 同路径，以服务令牌鉴权，不新增对外接口编号。货主选择器当前从账号目录派生，主数据目录落地后替换数据源，不改变 API-020 契约。

### 鉴权落点

功能权限在接口入口的统一鉴权切面校验；数据域在仓储层按登录上下文注入归属货主或本仓范围。运营端可查看全量但不改变档案归属；单仓端只能对本仓缓存发起变更申请。越权请求返回无权限或目标不存在，不泄露数据内容。

### 错误码映射

| 错误码 | HTTP | 触发条件 | 可重试 | 后端落点 |
|---|---|---|---|---|
| `PARAM_INVALID` | 400 | 字段缺失、越界或枚举非法 | 否 | 入口校验 |
| `RULE_DUPLICATE_CODE` | 422 | 货主内编码重复 | 否 | 提交判重 |
| `RULE_DUPLICATE_BARCODE` | 422 | 货主内条码重复 | 否 | 提交判重 |
| `STATE_INVALID` | 409 | 状态迁移不在守卫表内 | 否 | Kernel 状态机 |
| `CONFLICT_VERSION` | 409 | 版本号落后 | 是 | 乐观锁 |
| `NOT_FOUND_ARCHIVE` | 404 | 档案不存在或不在数据域内 | 否 | 仓储查询 |
| `FORBIDDEN_OWNER` | 403 | 货主越权访问他人档案 | 否 | 数据域注入 |
| `FORBIDDEN_SITE` | 403 | 单仓越权发起变更申请 | 否 | 数据域注入 |
| `IDEMPOTENT_IN_PROGRESS` | 409 | 幂等记录处理中 | 是 | 幂等入口 |
| `IDEMPOTENT_KEY_CONFLICT` | 409 | 同键异体 | 否 | 幂等入口 |
| `RATE_LIMITED` | 429 | 端加货主维度超限 | 是 | 限流切面 |
| `DEPENDENCY_UNAVAILABLE` | 503 | 下行或缓存依赖不可用 | 是 | 依赖调用 |
| `INTERNAL_ERROR` | 500 | 未捕获系统异常 | 否 | 全局异常处理器 |

### 可观测与兼容回滚

关键日志字段包含操作人、端、归属货主、档案编号、版本号、动作与受理号，满足 D24 留痕三元组。灰度通过配置开关控制单仓缓存下行，关闭时单仓只展示已有缓存与待刷新提示。回滚保留新增表与数据，只停用新接口入口；Flyway 脚本不反向执行，存量数据不删除。

## API 接口契约

### 通用约定

| 项 | 约定 |
|---|---|
| 基础路径与端分组 | 货主端 `/api/owner`、运营端 `/api/hub`、单仓端 `/api/site`；端之间路径不混用 |
| 认证方式 | 货主与单仓使用各自令牌，运营使用后台令牌；令牌放请求头，按 D15 一套契约 |
| 错误响应结构 | 所有错误体含 `code`、`message`、`traceId`、`retryable`；限流与处理中必须在响应体含 `retryAfterMs`，可带 `dataTimestamp`、`details`。`code` 为字符串符号码，成功码固定为字符串 `"0"`；平台响应包把数字 `0` 与字符串 `"0"` 都判为成功，身份模块数字码留待独立变更收敛。令牌无效或过期沿用平台既有 401 与数字错误码，不进入档案错误码表 |
| 幂等约定 | 写接口统一读 `Idempotency-Key` 头，作用域为归属货主加接口加键，窗口不少于二十四小时；同键同指纹返回首次结果，处理中返回可重试错误，同键异体返回冲突 |
| 分页约定 | 列表只用键集分页，参数 `cursor` 与 `limit`，默认二十、上限一百；响应带下一页游标与是否还有数据 |
| 时间与金额口径 | 时间用 UTC 时间戳；数量与重量用字符串承载定点数，禁止浮点；本变更不涉及金额 |
| 枚举约定 | 状态取 `DRAFT`、`PENDING`、`ACTIVE`、`REJECTED`、`DISABLED`；未知值按参数错误拒绝 |
| 限流与超时 | 限流维度为端加货主加接口；服务端预算写接口八百毫秒、查询接口五百毫秒，超限返回 `RATE_LIMITED` 并带重试间隔 |
| 降级约定 | 查询可降级为缓存数据且必须带 `dataTimestamp`；权限与状态校验不可用时不放行写操作 |

### 字段结构与枚举

请求与响应字段统一使用下表英文名，页面与服务端不得另起同义字段。除特别说明外，标识一律用十进制字符串，数量与重量用字符串承载定点数。

| 字段 | 类型与约束 | 出现位置 |
|---|---|---|
| `archiveId` | 字符串，系统编号 | 路径参数、响应 |
| `ownerId` | 字符串，归属货主，运营代建必填 | 请求体、响应、筛选 |
| `ownerCode` | 字符串，货主系统编码，货主内判重 | 请求体、筛选、响应 |
| `name` | 字符串，1 至 128 字 | 请求体、筛选、响应 |
| `barcode` | 字符串，单值，货主内判重 | 请求体、响应 |
| `baseUnit` | 字符串，基本单位 | 请求体、响应 |
| `storageCondition` | 字符串，保管条件，取值全集按当前待定项处理 | 请求体、响应 |
| `spec` | 字符串，规格型号 | 请求体、响应 |
| `category` | 字符串，分类 | 请求体、筛选、响应 |
| `version` | 整数，非负，乐观锁版本号 | 请求体、响应 |
| `reason` | 字符串，停用或变更申请原因 | 请求体 |
| `requestNo` | 字符串，变更申请编号，路径参数与申请响应 | 路径参数、响应、筛选 |
| `reviewReason` | 字符串，审核驳回原因，驳回必填 | 审核请求体、响应 |
| `packageConversionRate` | 字符串，定点包装换算率 | 档案响应 |
| `items` | 数组，逐条结果 | 批量响应 |
| `archiveIds` | 字符串数组，批量条目编号，单次上限一百 | API-009 请求 |
| `nextCursor` | 字符串，下一页游标 | 列表响应 |
| `hasMore` | 布尔值，是否还有下一页 | 列表响应 |
| `dataTimestamp` | UTC 时间戳，降级数据来源时间 | 查询响应 |
| `acceptNo` | 字符串，受理号 | 写接口响应、受理查询路径 |
| `acceptStatus` | 枚举 `PROCESSING`、`SUCCEEDED`、`FAILED` | 受理查询响应 |
| `result` | 对象，受理完成后的结果摘要，未完成为空 | 受理查询响应 |
| `result.archiveId` | 字符串，档案编号 | 受理结果摘要 |
| `result.requestId` | 字符串，变更申请编号 | 受理结果摘要 |
| `result.items` | 数组，逐条结果；条目含 `archiveId`、`success`、`status`、`errorCode`、`message` | 受理结果摘要 |
| `result.total`、`result.succeeded`、`result.failed` | 整数，批量受理的总数、成功数与失败数；仅批量动作返回 | 受理结果摘要 |
| `sortBy` | 枚举 `updateTime`、`ownerCode`、`name`；档案列表分别按更新时间、货主编码与名称排序；变更申请列表分别按申请创建时间、归属货主标识与申请字段名（`name` 映射到申请字段）排序，状态不参与排序 | 列表查询参数 |
| `sortDir` | 枚举 `asc`、`desc`，缺省 `desc` | 列表查询参数 |
| `updatedFrom`、`updatedTo` | UTC 时间戳，更新时间范围 | 列表查询参数 |
| `siteId` | 字符串，本仓标识，单仓数据域唯一一仓 | 单仓令牌声明、筛选 |
| `changeField`、`targetValue` | 字符串，允许变更字段与目标值；字段闭集为 `name`、`spec`、`category`、`storageCondition`，`baseUnit` 不由单仓申请变更，需走运营改档 | 变更申请请求 |
| `ownerName` | 字符串，货主展示名 | API-020 响应 |
| `credential`、`refreshCredential` | 字符串，站点凭证与刷新凭证；刷新凭证用 httpOnly cookie 下发，不进响应体 | API-018 请求、API-019 cookie |
| `accessToken`、`permissions`、`expiresIn` | 字符串、字符串数组、整数，令牌、功能权限列表与有效期秒数 | API-018/019 响应 |

档案状态枚举为 `DRAFT`、`PENDING`、`ACTIVE`、`REJECTED`、`DISABLED`；变更申请状态枚举为 `PENDING`、`APPROVED`、`REJECTED`。未知枚举值返回 `PARAM_INVALID`。

### 写接口统一响应

所有写接口返回 HTTP `202`，响应体只含 `acceptNo` 与受理时的业务状态快照 `status`，不返回执行结果与最终版本号；结果通过 `API-015` 至 `API-017` 受理查询接口获取，或由回调事件推送。各写接口条目中出现的「返回目标状态、版本号」统一按本段解释，不按字面返回最终状态。

受理查询接口按端分设，查询参数为 `acceptNo`，响应含 `acceptStatus`、`result` 与失败时的错误码。`PROCESSING` 每 3 秒轮询，最长 60 秒；超时保留受理号并给刷新出口。

### 单仓端会话与权限

单仓端令牌由 Hub 认证服务签发，令牌声明含 `siteId` 与端标识 `SITE`；换取令牌走 `API-018`，续期走 `API-019`。功能权限来自 Hub 下行的权限副本，数据域取令牌中的唯一 `siteId`；缺少或多于一个 `siteId` 返回 `FORBIDDEN_SITE`。`/api/site/**` 由 Site 单元承载，读取本仓缓存；变更申请经 Site 单元上行后写入 Hub 权威表。

### 鲁棒性约定

输入校验在入口完成，字段长度、枚举闭集与请求体大小超限直接返回参数错误，不进入业务逻辑。写接口按幂等约定去重；查询接口无副作用。依赖调用预算小于接口总预算，剩余预算不足时快速失败，不用空结果掩盖故障。批量接口返回与输入条数一致的结果明细，单条失败不影响其他条目。限流按端与货主分池，外部流量不影响单仓查询通道。

### 错误码表

| 错误码 | HTTP | 重试 | 触发条件 | 调用方处置 | 出现位置 |
|---|---|---|---|---|---|
| `PARAM_INVALID` | 400 | 否 | 字段缺失、越界或枚举非法 | 按字段提示修正 | 同步响应 |
| `RULE_DUPLICATE_CODE` | 422 | 否 | 货主内编码重复 | 修改编码后重提 | 同步响应 |
| `RULE_DUPLICATE_BARCODE` | 422 | 否 | 货主内条码重复 | 修改条码后重提 | 同步响应 |
| `STATE_INVALID` | 409 | 否 | 状态迁移不在守卫表内 | 刷新后按当前状态操作 | 同步响应 |
| `CONFLICT_VERSION` | 409 | 是 | 版本号落后 | 刷新并重新提交 | 同步响应 |
| `NOT_FOUND_ARCHIVE` | 404 | 否 | 档案不存在或不在数据域内 | 修正档案编号 | 同步响应 |
| `FORBIDDEN_OWNER` | 403 | 否 | 货主越权访问他人档案 | 换用有权限账号 | 同步响应 |
| `FORBIDDEN_SITE` | 403 | 否 | 单仓越权发起变更申请 | 换本仓可见档案 | 同步响应 |
| `IDEMPOTENT_IN_PROGRESS` | 409 | 是 | 幂等记录处理中 | 原键按重试间隔重试 | 同步响应 |
| `IDEMPOTENT_KEY_CONFLICT` | 409 | 否 | 同键异体 | 换键重新提交 | 同步响应 |
| `RATE_LIMITED` | 429 | 是 | 端加货主维度超限 | 按重试间隔退避 | 同步响应 |
| `DEPENDENCY_UNAVAILABLE` | 503 | 是 | 下行或缓存依赖不可用 | 退避重试或查看缓存 | 同步响应与查询响应 |
| `INTERNAL_ERROR` | 500 | 否 | 未捕获系统异常 | 带追踪编号报障 | 同步响应 |

### API-001 货主创建商品档案草稿

| 项 | 内容 |
|---|---|
| 方法与路径 | `POST /api/owner/archives` |
| 端与角色 | 货主端，货主用户 |
| 鉴权 | 货主令牌，功能权限 `archive.owner.write`，数据域注入归属货主 |
| 请求 | 请求体含名称、货主系统编码、条码、基本单位、保管条件与附属字段；未知字段拒绝 |
| 响应 | 返回 `202`、`acceptNo` 与受理时状态快照 `DRAFT`，不含最终版本号 |
| 错误码 | `PARAM_INVALID`、`FORBIDDEN_OWNER`、`IDEMPOTENT_IN_PROGRESS`、`IDEMPOTENT_KEY_CONFLICT`、`RATE_LIMITED`、`INTERNAL_ERROR` |
| 超时预算 | 服务端八百毫秒；建议客户端一秒，最多重试两次，使用同一幂等键 |
| 限流 | 货主端加归属货主加接口，超限返回 `RATE_LIMITED` 并带重试间隔 |
| 降级 | 不降级写入；依赖不可用时返回 `DEPENDENCY_UNAVAILABLE` |
| 并发冲突 | 新建无版本冲突；同编码判重在提交环节执行 |
| 幂等键 | `Idempotency-Key`，取客户端请求号 |
| 作用域 | 归属货主加接口加键 |
| 窗口 | 二十四小时 |
| 重复与处理中 | 重复返回首次受理号，处理中返回 `IDEMPOTENT_IN_PROGRESS` |
| 同键异体 | 返回 `IDEMPOTENT_KEY_CONFLICT`，首次结果不变 |
| 验收用例 | 首次、重复、十次并发重复、同键异体、字段越界、超限各一条 |
| 关联不变量 | INV-06、INV-18、INV-19 |

### API-002 货主提交档案审核

| 项 | 内容 |
|---|---|
| 方法与路径 | `POST /api/owner/archives/{id}/submit` |
| 端与角色 | 货主端，货主用户 |
| 鉴权 | 货主令牌，功能权限 `archive.owner.write`，数据域注入归属货主 |
| 请求 | 路径参数档案编号，请求体可空；提交前校验必填字段 |
| 响应 | 返回 `202`、`acceptNo` 与受理时状态快照 `DRAFT`，不含执行结果 |
| 错误码 | `PARAM_INVALID`、`RULE_DUPLICATE_CODE`、`RULE_DUPLICATE_BARCODE`、`STATE_INVALID`、`NOT_FOUND_ARCHIVE`、`FORBIDDEN_OWNER`、`IDEMPOTENT_IN_PROGRESS`、`IDEMPOTENT_KEY_CONFLICT`、`RATE_LIMITED`、`INTERNAL_ERROR` |
| 超时预算 | 服务端八百毫秒；最多重试两次，使用同一幂等键 |
| 限流 | 货主端加归属货主加接口，超限返回 `RATE_LIMITED` |
| 降级 | 判重依赖不可用时拒绝提交，返回 `DEPENDENCY_UNAVAILABLE` |
| 并发冲突 | 携带版本号，冲突返回 `CONFLICT_VERSION` |
| 幂等键 | `Idempotency-Key`，取客户端请求号 |
| 作用域 | 归属货主加接口加键 |
| 窗口 | 二十四小时 |
| 重复与处理中 | 重复返回首次受理号，处理中返回 `IDEMPOTENT_IN_PROGRESS` |
| 同键异体 | 返回 `IDEMPOTENT_KEY_CONFLICT` |
| 验收用例 | 首次、重复、十次并发重复、重复编码、状态非法各一条 |
| 关联不变量 | INV-06、INV-19 |

### API-003 货主编辑档案草稿

| 项 | 内容 |
|---|---|
| 方法与路径 | `PUT /api/owner/archives/{id}` |
| 端与角色 | 货主端，货主用户 |
| 鉴权 | 货主令牌，功能权限 `archive.owner.write`，数据域注入归属货主 |
| 请求 | 路径参数档案编号，请求体含允许修改字段与版本号 |
| 响应 | 返回 `202`、`acceptNo` 与受理时状态快照，不含最终版本号 |
| 错误码 | `PARAM_INVALID`、`STATE_INVALID`、`CONFLICT_VERSION`、`NOT_FOUND_ARCHIVE`、`FORBIDDEN_OWNER`、`IDEMPOTENT_IN_PROGRESS`、`IDEMPOTENT_KEY_CONFLICT`、`RATE_LIMITED`、`INTERNAL_ERROR` |
| 超时预算 | 服务端八百毫秒；最多重试两次 |
| 限流 | 货主端加归属货主加接口 |
| 降级 | 不降级写入；依赖不可用返回 `DEPENDENCY_UNAVAILABLE` |
| 并发冲突 | 乐观锁版本比对，冲突返回 `CONFLICT_VERSION` |
| 幂等键 | `Idempotency-Key`，取客户端请求号 |
| 作用域 | 归属货主加接口加键 |
| 窗口 | 二十四小时 |
| 重复与处理中 | 重复返回首次受理结果，处理中返回 `IDEMPOTENT_IN_PROGRESS` |
| 同键异体 | 返回 `IDEMPOTENT_KEY_CONFLICT` |
| 验收用例 | 首次、重复、十次并发重复、版本冲突、启用态编辑各一条 |
| 关联不变量 | INV-06、INV-18、INV-19 |

### API-004 货主停用档案

| 项 | 内容 |
|---|---|
| 方法与路径 | `POST /api/owner/archives/{id}/disable` |
| 端与角色 | 货主端，货主用户 |
| 鉴权 | 货主令牌，功能权限 `archive.owner.write`，数据域注入归属货主 |
| 请求 | 路径参数档案编号，请求体含停用原因与版本号 |
| 响应 | 返回 `202`、`acceptNo` 与受理时状态快照 `ACTIVE`，不含执行结果 |
| 错误码 | `PARAM_INVALID`、`STATE_INVALID`、`CONFLICT_VERSION`、`NOT_FOUND_ARCHIVE`、`FORBIDDEN_OWNER`、`IDEMPOTENT_IN_PROGRESS`、`IDEMPOTENT_KEY_CONFLICT`、`RATE_LIMITED`、`INTERNAL_ERROR` |
| 超时预算 | 服务端八百毫秒；最多重试两次 |
| 限流 | 货主端加归属货主加接口 |
| 降级 | 不降级写入；依赖不可用返回 `DEPENDENCY_UNAVAILABLE` |
| 并发冲突 | 乐观锁版本比对，冲突返回 `CONFLICT_VERSION` |
| 幂等键 | `Idempotency-Key`，取档案编号加动作 |
| 作用域 | 归属货主加接口加键 |
| 窗口 | 二十四小时 |
| 重复与处理中 | 重复返回首次受理结果，处理中返回 `IDEMPOTENT_IN_PROGRESS` |
| 同键异体 | 返回 `IDEMPOTENT_KEY_CONFLICT` |
| 验收用例 | 首次、重复、十次并发重复、状态非法、历史单据追溯各一条 |
| 关联不变量 | INV-18、INV-19 |

### API-005 货主查询自有档案

| 项 | 内容 |
|---|---|
| 方法与路径 | `GET /api/owner/archives` |
| 端与角色 | 货主端，货主用户 |
| 鉴权 | 货主令牌，功能权限 `archive.owner.read`，数据域注入归属货主 |
| 请求 | 查询参数含 `ownerCode`、`name`、状态、`category`、`updatedFrom`、`updatedTo`、`sortBy`、`sortDir` 与键集分页游标 |
| 响应 | 返回档案摘要列表、下一页游标与是否有下一页 |
| 错误码 | `PARAM_INVALID`、`FORBIDDEN_OWNER`、`RATE_LIMITED`、`INTERNAL_ERROR` |
| 超时预算 | 服务端五百毫秒；最多重试一次 |
| 限流 | 货主端加归属货主加接口 |
| 降级 | 查询降级时带 `dataTimestamp` 与来源标识 |
| 并发冲突 | 不适用，天然幂等 |
| 幂等键 | 不适用，天然幂等 |
| 作用域 | 不适用 |
| 窗口 | 不适用 |
| 重复与处理中 | 重复查询返回相同结果，无处理中状态 |
| 同键异体 | 不适用 |
| 验收用例 | 筛选、翻页、越权查询、降级各一条 |
| 关联不变量 | INV-06、INV-19 |

### API-006 运营代建商品档案草稿

| 项 | 内容 |
|---|---|
| 方法与路径 | `POST /api/hub/archives` |
| 端与角色 | 运营端，主数据管理员 |
| 鉴权 | 后台令牌，功能权限 `archive.hub.create`，请求体指定归属货主 |
| 请求 | 请求体含归属货主、名称、编码、条码、基本单位与保管条件 |
| 响应 | 返回 `202`、`acceptNo` 与受理时状态快照 `DRAFT`，不含最终版本号 |
| 错误码 | `PARAM_INVALID`、`FORBIDDEN_OWNER`、`IDEMPOTENT_IN_PROGRESS`、`IDEMPOTENT_KEY_CONFLICT`、`RATE_LIMITED`、`INTERNAL_ERROR` |
| 超时预算 | 服务端八百毫秒；最多重试两次 |
| 限流 | 运营端加接口，超限返回 `RATE_LIMITED` |
| 降级 | 不降级写入；依赖不可用返回 `DEPENDENCY_UNAVAILABLE` |
| 并发冲突 | 新建无版本冲突 |
| 幂等键 | `Idempotency-Key`，取客户端请求号 |
| 作用域 | 指定归属货主加接口加键 |
| 窗口 | 二十四小时 |
| 重复与处理中 | 重复返回首次受理号，处理中返回 `IDEMPOTENT_IN_PROGRESS` |
| 同键异体 | 返回 `IDEMPOTENT_KEY_CONFLICT` |
| 验收用例 | 首次、重复、十次并发重复、未指定货主、超限各一条 |
| 关联不变量 | INV-06、INV-19 |

### API-007 运营查询全量档案

| 项 | 内容 |
|---|---|
| 方法与路径 | `GET /api/hub/archives` |
| 端与角色 | 运营端，主数据管理员 |
| 鉴权 | 后台令牌，功能权限 `archive.hub.read`，数据域为全量 |
| 请求 | 查询参数含 `ownerId`、编码、状态、`category`、`updatedFrom`、`updatedTo`、`sortBy`、`sortDir` 与键集分页游标 |
| 响应 | 返回全量档案摘要列表、下一页游标与是否有下一页 |
| 错误码 | `PARAM_INVALID`、`RATE_LIMITED`、`INTERNAL_ERROR` |
| 超时预算 | 服务端五百毫秒；最多重试一次 |
| 限流 | 运营端加接口 |
| 降级 | 查询降级时带 `dataTimestamp` 与来源标识 |
| 并发冲突 | 不适用，天然幂等 |
| 幂等键 | 不适用，天然幂等 |
| 作用域 | 不适用 |
| 窗口 | 不适用 |
| 重复与处理中 | 重复查询返回相同结果 |
| 同键异体 | 不适用 |
| 验收用例 | 全量筛选、翻页、无权限、降级各一条 |
| 关联不变量 | INV-19 |

### API-008 运营审核单条档案

| 项 | 内容 |
|---|---|
| 方法与路径 | `POST /api/hub/archives/{id}/review` |
| 端与角色 | 运营端，主数据管理员 |
| 鉴权 | 后台令牌，功能权限 `archive.hub.review`，数据域为全量 |
| 请求 | 路径参数 `archiveId`，请求体含 `action`（`APPROVE` 或 `REJECT`）与 `reviewReason`，驳回时必填 |
| 响应 | 返回 `202`、`acceptNo` 与受理时状态快照 `PENDING`，不含执行结果 |
| 错误码 | `PARAM_INVALID`、`STATE_INVALID`、`CONFLICT_VERSION`、`NOT_FOUND_ARCHIVE`、`IDEMPOTENT_IN_PROGRESS`、`IDEMPOTENT_KEY_CONFLICT`、`RATE_LIMITED`、`INTERNAL_ERROR` |
| 超时预算 | 服务端八百毫秒；最多重试两次 |
| 限流 | 运营端加接口 |
| 降级 | 不降级写入；依赖不可用返回 `DEPENDENCY_UNAVAILABLE` |
| 并发冲突 | 状态机守卫加乐观锁，冲突返回 `STATE_INVALID` 或 `CONFLICT_VERSION` |
| 幂等键 | `Idempotency-Key`，取档案编号加审核动作 |
| 作用域 | 运营端加接口加键 |
| 窗口 | 二十四小时 |
| 重复与处理中 | 重复返回首次受理结果，处理中返回 `IDEMPOTENT_IN_PROGRESS` |
| 同键异体 | 返回 `IDEMPOTENT_KEY_CONFLICT` |
| 验收用例 | 通过、驳回无原因、重复、十次并发重复、状态已变各一条 |
| 关联不变量 | INV-18、INV-19 |

### API-009 运营批量操作档案

| 项 | 内容 |
|---|---|
| 方法与路径 | `POST /api/hub/archives/batch` |
| 端与角色 | 运营端，主数据管理员 |
| 鉴权 | 后台令牌，功能权限 `archive.hub.review`，数据域为全量 |
| 请求 | 请求体含 `archiveIds` 数组、`action`（`APPROVE`、`REJECT` 或 `DISABLE`）与 `reason`，驳回或停用时必填，单次上限一百 |
| 响应 | 返回 `202`、`acceptNo` 与受理时状态快照；逐条结果通过 `API-016` 查询 |
| 错误码 | `PARAM_INVALID`、`STATE_INVALID`、`CONFLICT_VERSION`、`NOT_FOUND_ARCHIVE`、`IDEMPOTENT_IN_PROGRESS`、`IDEMPOTENT_KEY_CONFLICT`、`RATE_LIMITED`、`INTERNAL_ERROR` |
| 超时预算 | 服务端八百毫秒；最多重试两次 |
| 限流 | 运营端加接口，超上限在入口拒绝 |
| 降级 | 不降级写入；依赖不可用返回 `DEPENDENCY_UNAVAILABLE` |
| 并发冲突 | 逐条版本比对，单条冲突不影响其他条目 |
| 幂等键 | `Idempotency-Key`，取客户端请求号 |
| 作用域 | 运营端加接口加键 |
| 窗口 | 二十四小时 |
| 重复与处理中 | 重复返回首次批量结果，处理中返回 `IDEMPOTENT_IN_PROGRESS` |
| 同键异体 | 返回 `IDEMPOTENT_KEY_CONFLICT` |
| 验收用例 | 全成、部分失败、重复、十次并发重复、超上限各一条 |
| 关联不变量 | INV-18、INV-19 |

### API-010 单仓查询本仓档案

| 项 | 内容 |
|---|---|
| 方法与路径 | `GET /api/site/archives` |
| 端与角色 | 单仓端，仓管员 |
| 鉴权 | 单仓令牌，功能权限 `archive.site.read`，数据域注入本仓 |
| 请求 | 查询参数含 `ownerCode`、`name`、`category`、`updatedFrom`、`updatedTo`、`sortBy`、`sortDir` 与键集分页游标 |
| 响应 | 返回本仓生效档案摘要、版本号、数据时间戳与下一页游标 |
| 错误码 | `PARAM_INVALID`、`FORBIDDEN_SITE`、`RATE_LIMITED`、`DEPENDENCY_UNAVAILABLE`、`INTERNAL_ERROR` |
| 超时预算 | 服务端五百毫秒；最多重试一次 |
| 限流 | 单仓端加本仓加接口，与货主流量分池 |
| 降级 | 权威同步不可用时返回缓存并带 `dataTimestamp` |
| 并发冲突 | 不适用，天然幂等 |
| 幂等键 | 不适用，天然幂等 |
| 作用域 | 不适用 |
| 窗口 | 不适用 |
| 重复与处理中 | 重复查询返回相同缓存结果 |
| 同键异体 | 不适用 |
| 验收用例 | 本仓筛选、跨仓越权、缓存降级、版本落后各一条 |
| 关联不变量 | INV-06、INV-19 |

### API-011 单仓发起变更申请

| 项 | 内容 |
|---|---|
| 方法与路径 | `POST /api/site/archive-change-requests` |
| 端与角色 | 单仓端，仓管员 |
| 鉴权 | 单仓令牌，功能权限 `archive.site.change`，数据域注入本仓 |
| 请求 | 请求体含档案编号、变更字段、目标值与原因 |
| 响应 | 返回 `202`、`acceptNo` 与受理时状态快照，申请编号在受理结果中给出 |
| 错误码 | `PARAM_INVALID`、`STATE_INVALID`、`NOT_FOUND_ARCHIVE`、`FORBIDDEN_SITE`、`IDEMPOTENT_IN_PROGRESS`、`IDEMPOTENT_KEY_CONFLICT`、`RATE_LIMITED`、`INTERNAL_ERROR` |
| 超时预算 | 服务端八百毫秒；最多重试两次 |
| 限流 | 单仓端加本仓加接口 |
| 降级 | 不降级写入；依赖不可用返回 `DEPENDENCY_UNAVAILABLE` |
| 并发冲突 | 同一档案同时只允许一条待审核申请，冲突返回 `STATE_INVALID` |
| 幂等键 | `Idempotency-Key`，取客户端请求号 |
| 作用域 | 本仓加接口加键 |
| 窗口 | 二十四小时 |
| 重复与处理中 | 重复返回首次受理号，处理中返回 `IDEMPOTENT_IN_PROGRESS` |
| 同键异体 | 返回 `IDEMPOTENT_KEY_CONFLICT` |
| 验收用例 | 首次、重复、十次并发重复、非本仓档案、重复待审各一条 |
| 关联不变量 | INV-06、INV-19 |

### API-012 运营查询变更申请

| 项 | 内容 |
|---|---|
| 方法与路径 | `GET /api/hub/archive-change-requests` |
| 端与角色 | 运营端，主数据管理员 |
| 鉴权 | 后台令牌，功能权限 `archive.hub.read`，数据域为全量 |
| 请求 | 查询参数含 `ownerId`、`siteId`、状态、`sortBy`、`sortDir` 与键集分页游标 |
| 响应 | 返回申请列表、下一页游标与是否有下一页 |
| 错误码 | `PARAM_INVALID`、`RATE_LIMITED`、`INTERNAL_ERROR` |
| 超时预算 | 服务端五百毫秒；最多重试一次 |
| 限流 | 运营端加接口 |
| 降级 | 查询降级时带 `dataTimestamp` |
| 并发冲突 | 不适用，天然幂等 |
| 幂等键 | 不适用，天然幂等 |
| 作用域 | 不适用 |
| 窗口 | 不适用 |
| 重复与处理中 | 重复查询返回相同结果 |
| 同键异体 | 不适用 |
| 验收用例 | 筛选、翻页、无权限、降级各一条 |
| 关联不变量 | INV-19 |

### API-013 运营审核变更申请

| 项 | 内容 |
|---|---|
| 方法与路径 | `POST /api/hub/archive-change-requests/{id}/review` |
| 端与角色 | 运营端，主数据管理员 |
| 鉴权 | 后台令牌，功能权限 `archive.hub.review`，数据域为全量 |
| 请求 | 路径参数 `requestNo`，请求体含 `action`（`APPROVE` 或 `REJECT`）与 `reviewReason`，驳回时必填 |
| 响应 | 返回 `202`、`acceptNo` 与受理时状态快照 `PENDING`，不含执行结果 |
| 错误码 | `PARAM_INVALID`、`STATE_INVALID`、`CONFLICT_VERSION`、`NOT_FOUND_ARCHIVE`、`IDEMPOTENT_IN_PROGRESS`、`IDEMPOTENT_KEY_CONFLICT`、`RATE_LIMITED`、`INTERNAL_ERROR` |
| 超时预算 | 服务端八百毫秒；最多重试两次 |
| 限流 | 运营端加接口 |
| 降级 | 不降级写入；依赖不可用返回 `DEPENDENCY_UNAVAILABLE` |
| 并发冲突 | 状态机守卫加乐观锁，冲突返回 `STATE_INVALID` 或 `CONFLICT_VERSION` |
| 幂等键 | `Idempotency-Key`，取申请编号加审核动作 |
| 作用域 | 运营端加接口加键 |
| 窗口 | 二十四小时 |
| 重复与处理中 | 重复返回首次受理结果，处理中返回 `IDEMPOTENT_IN_PROGRESS` |
| 同键异体 | 返回 `IDEMPOTENT_KEY_CONFLICT` |
| 验收用例 | 通过、驳回无原因、重复、十次并发重复、审核后下行各一条 |
| 关联不变量 | INV-18、INV-19 |

### API-014 单仓查询变更申请状态

| 项 | 内容 |
|---|---|
| 方法与路径 | `GET /api/site/archive-change-requests` |
| 端与角色 | 单仓端，仓管员 |
| 鉴权 | 单仓令牌，功能权限 `archive.site.read`，数据域注入本仓 |
| 请求 | 查询参数含申请编号、状态、`sortBy`、`sortDir` 与键集分页游标；`archiveId` 可按档案过滤 |
| 响应 | 返回本仓申请列表、驳回原因与是否有下一页 |
| 错误码 | `PARAM_INVALID`、`FORBIDDEN_SITE`、`RATE_LIMITED`、`INTERNAL_ERROR` |
| 超时预算 | 服务端五百毫秒；最多重试一次 |
| 限流 | 单仓端加本仓加接口 |
| 降级 | 查询降级时带 `dataTimestamp` |
| 并发冲突 | 不适用，天然幂等 |
| 幂等键 | 不适用，天然幂等 |
| 作用域 | 不适用 |
| 窗口 | 不适用 |
| 重复与处理中 | 重复查询返回相同结果 |
| 同键异体 | 不适用 |
| 验收用例 | 本仓查询、跨仓越权、驳回原因展示、降级各一条 |
| 关联不变量 | INV-19 |

### API-015 货主查询受理结果

| 项 | 内容 |
|---|---|
| 方法与路径 | `GET /api/owner/archive-acceptances/{acceptNo}` |
| 端与角色 | 货主端，货主用户 |
| 鉴权 | 货主令牌，功能权限 `archive.owner.read`，数据域注入归属货主 |
| 请求 | 路径参数 `acceptNo`，无请求体 |
| 响应 | 返回 `acceptStatus`、`result`、失败错误码与 `dataTimestamp` |
| 错误码 | `PARAM_INVALID`、`NOT_FOUND_ARCHIVE`、`FORBIDDEN_OWNER`、`RATE_LIMITED`、`DEPENDENCY_UNAVAILABLE`、`INTERNAL_ERROR` |
| 超时预算 | 服务端五百毫秒；最多重试一次 |
| 限流 | 货主端加归属货主加接口 |
| 降级 | 受理存储不可用时带 `dataTimestamp` 并返回可重试错误 |
| 并发冲突 | 不适用，天然幂等 |
| 幂等键 | 不适用，天然幂等 |
| 作用域 | 不适用 |
| 窗口 | 不适用 |
| 重复与处理中 | 重复查询返回同一受理状态 |
| 同键异体 | 不适用 |
| 验收用例 | 处理中、成功、失败、跨货主越权、超时各一条 |
| 关联不变量 | INV-19 |

### API-016 运营查询受理结果

| 项 | 内容 |
|---|---|
| 方法与路径 | `GET /api/hub/archive-acceptances/{acceptNo}` |
| 端与角色 | 运营端，主数据管理员 |
| 鉴权 | 后台令牌，功能权限 `archive.hub.read`，数据域为全量 |
| 请求 | 路径参数 `acceptNo`，无请求体 |
| 响应 | 返回 `acceptStatus`、`result`、失败错误码与 `dataTimestamp` |
| 错误码 | `PARAM_INVALID`、`NOT_FOUND_ARCHIVE`、`RATE_LIMITED`、`DEPENDENCY_UNAVAILABLE`、`INTERNAL_ERROR` |
| 超时预算 | 服务端五百毫秒；最多重试一次 |
| 限流 | 运营端加接口 |
| 降级 | 受理存储不可用时带 `dataTimestamp` 并返回可重试错误 |
| 并发冲突 | 不适用，天然幂等 |
| 幂等键 | 不适用，天然幂等 |
| 作用域 | 不适用 |
| 窗口 | 不适用 |
| 重复与处理中 | 重复查询返回同一受理状态 |
| 同键异体 | 不适用 |
| 验收用例 | 处理中、成功、失败、批量逐条结果、超时各一条 |
| 关联不变量 | INV-19 |

### API-017 单仓查询受理结果

| 项 | 内容 |
|---|---|
| 方法与路径 | `GET /api/site/archive-acceptances/{acceptNo}` |
| 端与角色 | 单仓端，仓管员 |
| 鉴权 | 单仓令牌，功能权限 `archive.site.read`，数据域注入唯一 `siteId` |
| 请求 | 路径参数 `acceptNo`，无请求体 |
| 响应 | 返回 `acceptStatus`、`result`、失败错误码与 `dataTimestamp` |
| 错误码 | `PARAM_INVALID`、`NOT_FOUND_ARCHIVE`、`FORBIDDEN_SITE`、`RATE_LIMITED`、`DEPENDENCY_UNAVAILABLE`、`INTERNAL_ERROR` |
| 超时预算 | 服务端五百毫秒；最多重试一次 |
| 限流 | 单仓端加本仓加接口 |
| 降级 | 受理存储不可用时带 `dataTimestamp` 并返回可重试错误 |
| 并发冲突 | 不适用，天然幂等 |
| 幂等键 | 不适用，天然幂等 |
| 作用域 | 不适用 |
| 窗口 | 不适用 |
| 重复与处理中 | 重复查询返回同一受理状态 |
| 同键异体 | 不适用 |
| 验收用例 | 处理中、成功、失败、跨仓越权、超时各一条 |
| 关联不变量 | INV-19 |

### API-018 换取单仓端令牌

| 项 | 内容 |
|---|---|
| 方法与路径 | `POST /api/hub/auth/site-login` |
| 端与角色 | 认证入口，仓管员 |
| 鉴权 | 站点凭证，换取 `SITE` 端令牌；不依赖既有令牌 |
| 请求 | 请求体含 `siteCode`、`credential` 与 `clientRequestId` |
| 响应 | 返回 `accessToken`、`siteId`、`permissions` 与 `expiresIn`；刷新凭证用 httpOnly cookie 下发 |
| 错误码 | `PARAM_INVALID`、`FORBIDDEN_SITE`、`RATE_LIMITED`、`INTERNAL_ERROR` |
| 超时预算 | 服务端五百毫秒；最多重试两次 |
| 限流 | 认证入口加站点加接口 |
| 降级 | 不降级签发；依赖不可用返回 `DEPENDENCY_UNAVAILABLE` |
| 并发冲突 | 同一凭证并发换取只签发一个有效令牌 |
| 幂等键 | `Idempotency-Key`，取 `clientRequestId` |
| 作用域 | 站点加接口加键 |
| 窗口 | 二十四小时 |
| 重复与处理中 | 重复返回首次令牌，处理中返回 `IDEMPOTENT_IN_PROGRESS` |
| 同键异体 | 返回 `IDEMPOTENT_KEY_CONFLICT` |
| 验收用例 | 首次、重复、十次并发重复、错误凭证、多仓声明各一条 |
| 关联不变量 | INV-19 |

### API-019 续期单仓端令牌

| 项 | 内容 |
|---|---|
| 方法与路径 | `POST /api/hub/auth/refresh` |
| 端与角色 | 认证入口，已登录仓管员 |
| 鉴权 | 有效令牌加刷新凭证，端标识必须为 `SITE` |
| 请求 | 请求体含 `refreshCredential` 与 `clientRequestId`，刷新凭证可由 httpOnly cookie 带入 |
| 响应 | 返回新 `accessToken`、`siteId`、`permissions` 与 `expiresIn` |
| 错误码 | `PARAM_INVALID`、`FORBIDDEN_SITE`、`RATE_LIMITED`、`INTERNAL_ERROR` |
| 超时预算 | 服务端五百毫秒；最多重试两次 |
| 限流 | 认证入口加站点加接口 |
| 降级 | 不降级签发；依赖不可用返回 `DEPENDENCY_UNAVAILABLE` |
| 并发冲突 | 同一刷新凭证并发只产生一个新令牌 |
| 幂等键 | `Idempotency-Key`，取 `clientRequestId` |
| 作用域 | 站点加接口加键 |
| 窗口 | 二十四小时 |
| 重复与处理中 | 重复返回首次新令牌，处理中返回 `IDEMPOTENT_IN_PROGRESS` |
| 同键异体 | 返回 `IDEMPOTENT_KEY_CONFLICT` |
| 验收用例 | 首次、重复、十次并发重复、过期凭证、端标识错误各一条 |
| 关联不变量 | INV-19 |

### API-020 运营查询货主选择器

| 项 | 内容 |
|---|---|
| 方法与路径 | `GET /api/hub/owners` |
| 端与角色 | 运营端，主数据管理员 |
| 鉴权 | 后台令牌，功能权限 `archive.hub.create`，数据域为全量 |
| 请求 | 查询参数含 `ownerCode`、`name` 与键集分页游标；`ownerCode` 按货主标识精确匹配，`name` 按展示名模糊匹配，两者同时给出时取交集 |
| 响应 | 返回 `ownerId`、`ownerName`、下一页游标与是否有下一页；仅在降级时带 `dataTimestamp` |
| 错误码 | `PARAM_INVALID`、`RATE_LIMITED`、`INTERNAL_ERROR` |
| 超时预算 | 服务端五百毫秒；最多重试一次 |
| 限流 | 运营端加接口 |
| 降级 | 查询降级时带 `dataTimestamp` |
| 并发冲突 | 不适用，天然幂等 |
| 幂等键 | 不适用，天然幂等 |
| 作用域 | 不适用 |
| 窗口 | 不适用 |
| 重复与处理中 | 重复查询返回相同结果 |
| 同键异体 | 不适用 |
| 验收用例 | 编码筛选、名称筛选、翻页、无权限、降级各一条 |
| 关联不变量 | INV-19 |

### 事件与回调

审核通过后发布档案生效事件，事件标识为档案编号加版本号；下行摘要含档案编号、版本号、数据时间戳与变更字段摘要，不含内部实体。事件至少一次投递，消费方按事件标识去重；失败进入重试队列并告警。变更申请审核结果同事件通知单仓，失败事件也使用错误码表中的码。

### 契约验收

| 验收项 | 可判定标准 |
|---|---|
| 完整性 | 所有写接口均有条目级幂等要素、超时预算、限流维度、降级行为与错误码 |
| 幂等 | 每个写接口十次并发重复只产生一个业务结果，受理号一致；同键异体返回冲突 |
| 错误码 | 错误码表每行至少一个触发用例，错误体含四个必填字段 |
| 状态机 | 草稿到待审核、通过、驳回、停用、恢复各路径有正例，非法迁移有反例 |
| 数据域 | 货主越权、单仓跨仓各至少一条反例，运营代建不改变归属 |
| 一致性 | 审核通过后版本号递增并下行；单仓缓存版本落后时标记待刷新 |
| 受理闭环 | 每个写接口返回受理号，受理查询从处理中到成功或失败有完整链路；轮询不超过 60 秒 |
| 契约一致 | OpenAPI 产物字段、错误码与幂等键与本段逐项一致 |

## Risks / Trade-offs

- 审核态新增会带来历史三态数据迁移：现有启用数据直接映射为启用，草稿保持草稿；迁移脚本只增不改，按版本号回填。
- 单仓缓存不是强一致：接受最终一致，代价是审核通过后短时间内可能看到旧版本；用数据时间戳与待刷新提示缓解。
- 审核通过才判重会让草稿存在重复编码：符合 `26` 口径，代价是提交时可能失败并退回草稿。

## Migration Plan

先发布数据库新表与状态枚举扩展，再发布后端接口，随后上线运营审核页、货主端页面与单仓端缓存。灰度期间关闭单仓下行开关，单仓只展示已有缓存；确认版本号与摘要一致后打开。回滚时停用新接口入口并保留数据，不执行反向删表脚本。

## Open Questions

无。审核态命名、货主写权、删除语义与单仓维护边界已由需求方确认；温区取值全集仍按当前有效版本的待定项处理，不在本次变更内决定。
