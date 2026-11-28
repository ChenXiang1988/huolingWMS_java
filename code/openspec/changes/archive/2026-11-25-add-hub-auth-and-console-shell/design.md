# Design

## 上下文与目标

后端目前只有四个可启动的空壳，没有数据库访问、认证与权限代码；前端工程目录尚未建立。`19` 已定四道门、七张表、权限点代码声明与数据域合并规则，`05` 已定运营端六个角色，`12` 与 `12a` 已定令牌、组件外壳与布局尺寸，`00` D20 已定「写入一律异步受理」。本设计只解决落位与实现方式，不重新讨论这些口径。

**目标**：运营端能登录、能按权限点控制菜单与按钮、能由权限管理员自助维护账号角色与数据域；前端有一套可复用的外壳与组件。

**不做**：单仓端、货主端与移动作业端的登录；多因素认证与单点登录；暗色主题；权限点的手工维护；行级安全策略；审计日志的归档与外部推送。

## 前端设计

### 工程结构

```
code/web/
  package.json          pnpm workspace
  apps/admin/           运营后台，Vite 端口 5173
  packages/design/      令牌入口与通用组件
  packages/request/     请求层与错误码映射
  packages/dict/        状态词表与术语
```

技术取 `01` 的选型：Vue 3 + TypeScript + Vite + Element Plus，状态用 Pinia，路由用 Vue Router。应用之间不互相引用，共享包之间不循环依赖。

### 外壳与路由

`UI-P04` 的尺寸落成三个组件：`AppShell`（顶栏 56 + 左侧菜单 + 内容区）、`AppSidebar`（展开 200／收起 64，折叠状态存本地）、`AppContent`（最小 1200、最大 1440 居中）。低于 1366 宽不重排，出现横向滚动条即可，不做移动端适配。

路由表静态声明，每条路由带 `meta.perm`（单个权限点编码）与 `meta.menu`（是否进菜单）。守卫顺序固定：

1. 无访问令牌 → 尝试用刷新令牌静默续期，失败跳登录页并记录 `redirect`
2. 有令牌但路由要求的权限点不在权限集内 → 无权限页（`UI-S03`），不跳登录页
3. 通过 → 渲染，同时把筛选条件与页码写入地址（`UI-X04`）

菜单由「路由表 ∩ 权限集」生成，无权限的项不渲染、不置灰（`UI-X06`）。

### 令牌与会话

| 项 | 做法 | 理由 |
|---|---|---|
| 访问令牌 | 只存内存（Pinia），刷新页面丢失 | 脚本读不到长期凭证，缩小 XSS 影响面 |
| 刷新令牌 | 服务端以 `HttpOnly` cookie 下发，路径限定 `/hub/api/auth` | 前端脚本不可读，只能用于续期 |
| 续期 | 路由守卫与请求层两处都可触发，并发续期只发一次 | 避免多个 401 同时触发刷新风暴 |
| 超时预警 | 按访问令牌到期时间提前 5 分钟提示续期（`UI-X05`） | 与契约里的有效期一致 |

登录页不渲染外壳，只有账号、密码与登录按钮，不放公告与自助找回密码入口。

### 写入必须经过受理环

按 `00` D20 与 `12` 的 `UI-I12`，本变更所有写操作都不直接返回结果：

| 阶段 | 表现 | 依据 |
|---|---|---|
| 提交 | 跳到「已受理」，展示**受理号**；不显示「成功」 | `UI-F01` |
| 处理中 | 状态标签「处理中」，每 3 秒轮询受理结果，最长 60 秒 | `UI-F02` |
| 结果到达 | 成功或失败整条替换状态；失败给重提入口并保留原输入 | `UI-F03` |
| 超时 | 转「稍后查看」，给受理号查询入口 | `UI-F02` |

受理号来自接口契约中的受理结果查询接口（API-018），前端只拿受理号轮询，不自行判断业务是否成功。

### 页面状态与降级

五种状态全部双编码（`UI-S01` 至 `UI-S05`）：加载用骨架屏，空数据给原因与出口，无权限整页说明加联系角色，接口失败在区域内展示错误码与人话说明并给重试，降级走顶部提示条并展示**数据时间戳**与刷新出口。降级提示条常驻到刷新成功，同页多个降级源只显示一条，取最早的**数据时间戳**。

### 通用组件

`packages/design` 封装下列外壳，页面只传数据与列配置：

| 组件 | 对应规格 | 关键约束 |
|---|---|---|
| `AppShell` | `UI-P04` | 顶栏、侧栏、内容区；尺寸取自令牌 |
| `DataTable` | `UI-C01` | 行高 40、紧凑 32、关闭斑马纹、操作列固定右侧且最多三个文字按钮 |
| `FilterBar` | `UI-C02`、`UI-C07` | 一行最多四项，超出折叠，折叠状态写地址；主操作最右 |
| `FormSection` | `UI-C03` | 分组标题与区块 |
| `FormField` | `UI-C03` | 标签右对齐、宽度 100 或 120、必填星号前置、错误与帮助文案在控件下方 4px |
| `FormInput` | `UI-C28` | 内描边、悬浮只加深描边不改尺寸、聚焦 2px 主色、错误态按 `UI-T25` |
| `ActionButton` | `UI-C27`、`UI-C29`、`UI-I03` | 加载态尺寸不变、弹窗触发态保持、前置不满足置灰并给原因 |
| `DetailDrawer` | `UI-C04` | 左目录右两列、段数 ≥3 才显示目录、底部操作右对齐 |
| `StatusTag` | `UI-C06` | `small`，语义色按 `UI-T02`~`UI-T05` |
| `AcceptPanel` | `UI-F01`~`UI-F03` | 受理号、轮询、超时出口 |

令牌只在 `packages/design/src/tokens.css` 定义一次，按当前版 `12` 的 `UI-T01`、`UI-T20`~`UI-T25` 与 `UI-T31` 覆盖色值，其余沿用 Element Plus 默认（`UI-T27`）；暖灰基底配近黑灰主按钮，橙色只作警告。业务页面出现十六进制色值即静态检查失败。

### 权限点编码

本变更新增的权限点统一为 `hub.<资源>.<动作>`：`hub.account.read`、`hub.account.create`、`hub.account.update`、`hub.account.role`、`hub.role.read`、`hub.role.create`、`hub.role.update`、`hub.role.assign`、`hub.role.scope`、`hub.audit.read`。这批编码**待并入权限矩阵**：`05` 的角色 × 模块矩阵只到模块级，按钮级编码由代码声明，矩阵的模块行在下次修订时补入。

### 页面与接口映射

| 页面 | 骨架 | 接口编号 |
|---|---|---|
| 登录 | 独立页 | API-001、API-002 |
| 工作台 | `UI-P02` | API-004 |
| 账号管理 | `UI-P01` + `UI-C04` | API-005、API-006、API-007、API-008、API-009、API-018 |
| 角色管理 | `UI-P01` + `UI-P09` | API-010、API-011、API-012、API-013、API-014、API-018 |
| 数据域配置 | `UI-P09` | API-015、API-016、API-018 |
| 审计日志 | `UI-P01` | API-017 |
| 受理结果 | 组件内嵌 | API-018 |

## 后端设计

### 包落位与接口承载

身份与权限属 `03` 的 M01，只在运营端运行，因此落在 `wms-hub`，不进 `wms-kernel`：

```
com.huoling.wms.masterdata.identity.domain          Account、Role、Permission、RoleScope 聚合
com.huoling.wms.masterdata.identity.application     登录、授权、账号用例、受理执行
com.huoling.wms.masterdata.identity.infrastructure  MyBatis-Plus 持久化、Redis 缓存、受理执行器
com.huoling.wms.masterdata.identity.api             Controller、请求响应对象
```

`wms-kernel` 保持只装库存、库位、任务这类跨单元共享的领域模型。ArchUnit 的 `..domain..` 与模块边界规则对两个模块同样生效。

十八个接口由四个 Controller 承载：认证 API-001、API-002、API-003、API-004；账号 API-005、API-006、API-007、API-008、API-009；角色与权限点 API-010、API-011、API-012、API-013、API-014；数据域与审计 API-015、API-016、API-017；受理查询 API-018。

### 表清单

九张表全部落 `wms-hub` 库，前缀 `wms_`，主键雪花，公共列按 `11` 的五组。表结构变更一律走 Flyway 迁移脚本。

| 表名 | 归属 | 脚本编号 |
|---|---|---|
| `wms_account` | M01 账号 | V20261124 |
| `wms_role` | M01 角色 | 同上 |
| `wms_permission` | M01 权限点 | 同上 |
| `wms_role_permission` | M01 角色权限点 | 同上 |
| `wms_account_role` | M01 账号角色 | 同上 |
| `wms_role_scope` | M01 数据域 | 同上 |
| `wms_role_scope_item` | M01 数据域明细 | 同上 |
| `wms_authorization_log` | M01 授权审计 | 同上 |
| `wms_acceptance` | M01 受理单 | 同上 |

### 核心表结构

```sql
create table wms_account (
  id            bigint       primary key,
  account_name  varchar(64)  not null,
  password_hash varchar(100) not null,
  employee_id   bigint,
  owner_id      bigint,
  status        varchar(16)  not null,
  version       bigint       not null default 0,
  created_at timestamptz not null, created_by bigint not null,
  updated_at timestamptz not null, updated_by bigint not null,
  constraint uk_wms_account_name unique (account_name),
  constraint uk_wms_account_employee unique (employee_id)
);

create table wms_role (
  id bigint primary key,
  role_code varchar(48) not null,
  role_name varchar(64) not null,
  applies_to_client varchar(16) not null,
  status varchar(16) not null,
  version bigint not null default 0,
  created_at timestamptz not null, created_by bigint not null,
  updated_at timestamptz not null, updated_by bigint not null,
  constraint uk_wms_role_code unique (role_code)
);

create table wms_permission (
  id bigint primary key,
  perm_code varchar(96) not null,
  module varchar(32) not null,
  status varchar(16) not null,
  created_at timestamptz not null, updated_at timestamptz not null,
  constraint uk_wms_permission_code unique (perm_code)
);

create table wms_role_permission (
  role_id bigint not null, permission_id bigint not null,
  primary key (role_id, permission_id)
);

create table wms_account_role (
  account_id bigint not null, role_id bigint not null,
  primary key (account_id, role_id)
);

create table wms_role_scope (
  id bigint primary key,
  role_id bigint not null,
  dimension varchar(16) not null,
  scope_type varchar(16) not null,
  constraint uk_wms_role_scope unique (role_id, dimension)
);

create table wms_role_scope_item (
  id bigint primary key,
  role_scope_id bigint not null,
  target_id bigint not null,
  constraint uk_wms_role_scope_item unique (role_scope_id, target_id)
);

create table wms_acceptance (
  id            bigint       primary key,
  accept_no     varchar(32)  not null,
  api_code      varchar(16)  not null,
  account_id    bigint       not null,
  idempotency_key varchar(64) not null,
  fingerprint   varchar(64)  not null,
  status        varchar(16)  not null,
  result_json   text,
  error_code    varchar(32),
  created_at    timestamptz  not null,
  finished_at   timestamptz,
  constraint uk_wms_acceptance_no unique (accept_no),
  constraint uk_wms_acceptance_idempotency_key unique (account_id, api_code, idempotency_key)
);
```

三处约束值得单独说明：

1. `uk_wms_account_employee` 在 PostgreSQL 对 NULL 不生效，正好满足「货主端账号不绑员工、可有多个为空」。
2. `wms_role_scope_item.target_id` 不建外键：它可能指向货主也可能指向仓，单列外键表达不了；有效性在应用层校验，目标归档后保留授权并标记失效。
3. `uk_wms_acceptance_idempotency_key` 是幂等的落点：同账号、同接口、同幂等键只允许一张受理单，并发插入撞唯一索引时回查首次受理单。`fingerprint` 只用于同键异体比对，不建唯一约束——按指纹去重会让「同键异体」被判成重复请求，反而拿不到 `COMMON_9005`。

### 不涉及的模型

本变更不建库存表。`04` 与 `11` 已把库存表的 `location_id NOT NULL`、七字段唯一键（`owner_id`、`location_id`、`sku_id`、`batch_no`、`stock_status`、`serial_no` 与容器）、`CHECK (qty_allocated <= qty_on_hand)` 定死，落点在后续库存变更；其中 `serial_no` 与容器列取 `NOT NULL DEFAULT ''`，不建部分唯一索引。本文不复述，也不在本变更的迁移脚本里创建任何库存表。

### 鉴权链路

| 顺序 | 组件 | 失败返回 |
|---|---|---|
| 一 | JWT 过滤器：验签、过期、`jti` 黑名单、端标识 | 401 |
| 二 | 权限拦截器：读 `@RequiresPermission`，比对权限集 | 403 |
| 三 | 数据域解析：固定域与配置域合并，结果为空 | 403 |
| 四 | 查询构造：显式传入 `DataScope` | — |

数据域采用**显式传参**而非 SQL 拦截器：列表查询方法签名必须带 `DataScope`。理由是隐式改写 SQL 在生产排障时看不出条件来源；首期表少，显式方案可控。

两条不变量的强制手段：

| 不变量 | 强制手段 |
|---|---|
| INV-06 | 仓储层统一注入数据域条件，业务代码不得手写货主或仓条件；单元测试断言无 `DataScope` 参数的列表查询方法数为零 |
| INV-19 | 权限拦截器校验功能权限，数据域解析校验数据域，缺一即拒；越权用例覆盖仅持功能权限与仅持数据域两种情形 |

### 权限点声明与启动校验

接口用 `@RequiresPermission("hub.account.read")` 声明。启动时扫描 `RequestMappingHandlerMapping`：白名单（登录、刷新、登出、健康检查、契约导出）之外未声明注解则启动失败；声明的权限点在 `wms_permission` 不存在则插入；表中有而代码中已无则置停用，不删除。扫描完成后全局权限版本号加一。

### 权限缓存

按决三采用**全局版本号**：

| 项 | 做法 |
|---|---|
| 键 | `hub:perm:{accountId}:{client}:{globalVersion}` |
| 值 | 权限点集合 + 合并后的数据域 |
| 有效期 | 30 分钟兜底 |
| 失效 | 任何授权动作提交后 `INCR hub:perm:version` |

版本号在事务提交后递增（`TransactionSynchronization.afterCommit`）。缓存读取失败降级查库，查库也失败返回 503，不默认放行。

### 密码与令牌

| 项 | 做法 |
|---|---|
| 密码存储 | BCrypt，强度 10 |
| 访问令牌 | JWT，HS256，30 分钟，载荷只有账号 ID、端标识、`jti`、签发与到期时间 |
| 刷新令牌 | 随机串存 Redis，7 天，服务端以 HttpOnly cookie 下发 |
| 登出 | `jti` 入黑名单，TTL 等于剩余有效期；同时删刷新令牌 |
| 登录限流 | 同 IP 与同账号 10 次/分钟，超限 429 |

密钥从配置注入，仓库内只留占位符。

### 异步受理的执行

写请求到达后同步完成三件事：校验参数、按请求指纹查受理表、写入受理单并返回受理号。业务执行交给进程内线程池，结果回写受理单。

| 项 | 做法 |
|---|---|
| 受理单状态 | 待处理、处理中、成功、失败 |
| 执行器 | 固定大小线程池，队列容量 200；队列满返回 429 |
| 重试 | 业务失败不回重试，由用户按受理号重提；数据库连接超时这类瞬时故障重试一次 |
| 幂等 | 同账号、同接口、同指纹只建一张受理单 |
| 查询 | 按受理号查状态与结果，只返回调用方可观察字段 |

首期不引入消息队列：配置类操作量级小，进程内执行足够；作业类异步行（计费事实、跨端下行）仍走 RabbitMQ，属后续变更。

### 授权事务与不变量

授权动作在单个事务内完成：写关联表 → 校验不变量 → 记审计 → 提交 → 版本号加一。三条不变量：操作者与目标账号相同返回 403；变更后可用权限管理员为零返回 409 并回滚；角色端标识与账号端标识不一致返回 400。审计表只授插入权限。

### 迁移与初始化

`code/db/migration/hub/V20261124__hub_identity.sql` 由 Flyway 执行，建九张表。角色初始化用可重复执行的 `R__hub_roles.sql`，按代码版本建六个角色。

角色与权限点的绑定不放 Flyway：权限点由启动扫描按代码声明写入，Flyway 执行时权限点表还是空的，SQL 绑定只能绑到零行。落点改为启动链：`PermissionStartupSync`（先写全局权限点）→ `RolePermissionInitializer`（再补角色绑定），顺序由 `@Order` 写死。基线由 `05` 第 201 行「账号、角色、权限」模块行机械推导：权限管理员取全部十个编码，主数据管理员只取 `hub.account.read`，其余四个角色不绑。初始化只在角色绑定为空时补一次，不按矩阵重置——运行时的权威路径是 API-013 整体替换且每次留审计，启动时重写会绕开审计。

**回填策略**：后续对已有数据的表新增 `NOT NULL` 字段，一律走「先加可空列 → 批量回填 → 再加非空约束」三步。本次九张表都是新建，不涉及回填。

初始管理员不写进迁移：应用启动时若 `wms_account` 为空且配置了引导参数，则创建一个权限管理员并强制首次登录改密；未配置则不创建并打日志。密码从环境变量注入。

### 错误码与异常映射

业务代码只抛携带常量码的 `IdentityException`，不自行决定 HTTP 状态；统一出口是 `GlobalExceptionHandler`。下表是全部 22 个码的落点，新增码必须同时补本表与契约章错误码表，两处缺一视为缺陷。

| 错误码 | HTTP | 后端落点 |
|---|---|---|
| `AUTH_1001` | 401 | `AuthService` 账号不存在或密码错，不区分原因 |
| `AUTH_1002` | 401 | JWT 过滤器验签与过期、`CurrentUser` 取不到身份、登出后黑名单命中 |
| `AUTH_1003` | 401 | `AuthService` 账号已停用 |
| `AUTH_1004` | 401 | `RefreshTokenStore` 取不到刷新令牌，含已被消费 |
| `AUTHZ_2001` | 403 | `PermissionInterceptor` 权限点缺失；`AuthService` 发现账号在运营端没有任何角色 |
| `AUTHZ_2002` | 403 | `DataScopeMerger` 合并结果为空；本变更无按货主或仓寻址的接口，API 层无触发路径 |
| `AUTHZ_2003` | 403 | 请求标识超出会话数据域；同样本变更无触发路径，保留给后续按域寻址接口 |
| `AUTHZ_2004` | 403 | `AuthorizationRules` 操作者与目标账号相同 |
| `AUTHZ_2005` | 409 | `AuthorizationRules` 变更后权限管理员为零 |
| `ACCOUNT_3001` | 409 | `AccountCommandService` 账号名已存在 |
| `ACCOUNT_3002` | 409 | `AccountCommandService` 员工已绑定其他账号 |
| `ACCOUNT_3003` | 404 | `AccountQueryService` 与 `AcceptanceService` 查不到账号 |
| `ROLE_4001` | 404 | `RoleService` 角色不存在 |
| `ROLE_4002` | 400 | `Role` 与 `AuthorizationRules` 的角色端标识不匹配 |
| `PERM_5001` | 400 | `RoleService` 权限点不存在或已停用 |
| `SCOPE_6001` | 400 | `ScopeDimension.parse` 维度闭集外 |
| `SCOPE_6002` | 400 | `RoleScope` 指定列表为空或全部范围携带标识 |
| `COMMON_9001` | 400 | 入口参数校验、JSON 解析失败、参数类型不匹配、闭集外取值、缺 `Idempotency-Key`、缺 `version` |
| `COMMON_9002` | 429 | `LoginRateLimiter` 超限，响应带 `Retry-After` |
| `COMMON_9003` | 503 | `DataAccessException` 兜底、受理表不可用 |
| `COMMON_9004` | 409 | 版本条件更新影响行数为零 |
| `COMMON_9005` | 409 | `AcceptanceService` 同键异体，指纹与首次不一致 |

## API 接口契约

统一前缀 `/hub/api`。响应包固定为：

```json
{ "code": 0, "message": "ok", "data": {}, "traceId": "tr-xxx", "retryable": false }
```

`code` 为 0 表示成功；非 0 时取错误码常量的数字段（`AUTH_1001` 在响应体里是 1001），错误码表是唯一对照。`retryable` 表示调用方是否可直接重试同一请求。分页响应固定 `{ "items": [], "total": 0, "page": 1, "size": 20 }`，页码从 1 起，单页最多 100 条。

各接口条目统一列八项：方法与路径、端与角色、鉴权、请求、响应、业务错误码、幂等、关联不变量；其后一段为该接口的超时、限流、降级、并发与验收用例。

### 标识、类型与枚举约定

| 项 | 约定 |
|---|---|
| 标识 | `id`、`accountId`、`employeeId`、`roleIds[]`、`targetIds[]` 出参一律为**十进制字符串**（OpenAPI `string`，`pattern` `^[1-9][0-9]*$`），库内仍是整数列，只在 HTTP 边界转换；入参接受字符串，数字形态按 `COMMON_9001` 拒绝，两端都不做隐式转换。理由是主键为雪花号（库内实际值如 `2125394920742858753`），超出 JavaScript 安全整数上界 `9007199254740991`，按 JSON 数字传输会在浏览器侧静默丢精度、回传后变成不存在的标识，前端无法自救。`version` 保持 JSON 整数、从 0 起 |
| 员工标识 | `employeeId` 是员工内部主键，由单仓端员工档案分配，不是工号；工号按仓编码生成且不可改（见 `20`「关键字段与约束」、`05`「员工号由仓编码生成且不可改」），不作为任何接口的入参或出参 |
| 账号标识 | 会话票据接口（API-001、API-002）叫 `accountId`，资源本体接口（API-004、API-005、API-007）叫 `id`，两者同为账号主键 |
| 时间 | 一律 UTC ISO-8601 带偏移（`2026-11-24T08:00:00Z`）；限流与退避等待用秒级 `Retry-After` |
| 金额与数量 | 本变更不返回金额与数量字段；后续出现时用定点字符串，不用浮点 |
| 空值与缺省 | 字段缺省表示不修改；`null` 表示清空，只在声明可为 `null` 的字段上允许；`employeeId` 在 API-007 响应中可为 `null`，货主端账号不绑员工（见 `19`「七张表，权限点不在其中新建」） |
| 未知字段 | 请求体出现契约外字段按忽略处理；契约内字段类型不符返回 `COMMON_9001`，不进入业务逻辑 |
| 枚举闭集 | 以下枚举表之外的值一律返回 `COMMON_9001`，不静默降级、不落库后靠存储约束兜底；`dimension` 例外，它的闭集外取值返回 `SCOPE_6001`（见 API-016） |

枚举按 `21` 的登记体例给出（分类维度、完整取值、权威文档、互斥证明、穷尽证明五项齐全）。`21` 现有十二类里没有「数据域范围类型」「数据域维度」「启停状态」三类，这三行标注待登记，编号是建议值：

| 编号 | 枚举 | 分类维度 | 完整取值 | 权威文档 | 互斥证明 | 穷尽证明 |
|---|---|---|---|---|---|---|
| E-13（待登记） | 数据域范围类型 | 范围是否收窄到具体标识 | `ALL`（全部）、`LIST`（指定列表）（2） | 本变更；语义见 `19`「三个字段决定隔离边界」 | 一个维度要么不收窄、要么收窄到具体标识，不能既是全部又带标识 | 收窄方式只有「不收窄」与「列举具体标识」两种，无第三种 |
| E-14（待登记） | 数据域维度 | 隔离边界 | `OWNER`（货主）、`WAREHOUSE`（仓）（2） | 本变更；语义见 `19`「三个字段决定隔离边界」 | 两值分别对应两种隔离边界，取值不重叠 | `19` 定死只有货主与仓两个维度，白名单外一律拒绝 |
| E-15（待登记） | 启停状态 | 对象是否可用 | `ENABLED`（启用）、`DISABLED`（停用）（2） | 本变更；语义见 `19`「七张表，权限点不在其中新建」 | 停用不物理删除（INV-18），两值互斥 | 可观察的可用性只有启用与停用两种 |
| E-01（已登记） | 端 | 使用者与部署位置 | 代码形态 `HUB`（运营端）、`OWNER`（货主端）、`SITE`（单仓端）、`MOBILE`（移动作业端）（4） | `21` E-01；本表只补代码形态，不新增取值 | 同 E-01 | 同 E-01；本变更只产生与校验 `HUB` |

`OWNER` 在 `client` 里是货主端、在 `dimension` 里是货主，两个字段各自独立取值，同名不通用：解析按字段判定，不按取值判定，也不允许把一个字段的值传给另一个字段。

### API-001 登录

| 项 | 内容 |
|---|---|
| 方法与路径 | `POST /auth/login` |
| 端与角色 | 运营端；六个角色共用，不区分 |
| 鉴权 | 免鉴权，白名单 |
| 请求 | `{accountName, password}`：均为字符串、必填；`accountName` 长度 3–64、只允许字母数字与 `.` `_` `-`；`password` 非空、长度上限 72（超出 BCrypt 有效长度，按 `COMMON_9001` 拒绝） |
| 响应 | `data = {accessToken, expiresIn, accountId, accountName, roles[], permissions[]}`：身份字段平铺，不用嵌套 `account`；`accessToken` 字符串，`expiresIn` 整数秒，`accountId` 账号主键，十进制字符串，`accountName` 字符串，`roles[]` 角色编码，`permissions[]` 权限点编码；刷新令牌走 `HttpOnly` cookie，不出现在响应体 |
| 业务错误码 | `AUTH_1001`、`AUTH_1003`、`AUTHZ_2001`、`COMMON_9001`、`COMMON_9002`、`COMMON_9003` |
| 幂等 | 不做幂等：认证动作重复执行各自签发新令牌；幂等键不适用、作用域不适用、窗口不适用、同键异体不适用 |
| 关联不变量 | INV-19 |

约束：超时预算 5 秒；限流按 IP 与账号各 10 次/分钟；降级不适用；并发下重复登录互不影响；验收用例覆盖登录成功、密码错误、账号停用、限流触发、缺字段返回 `COMMON_9001`、响应身份字段平铺为 `accountId` 与 `accountName`；运营端账号没有任何角色时拒绝签发令牌，返回 403 `AUTHZ_2001`，不允许「登录成功但菜单全空」。

### API-002 刷新令牌

| 项 | 内容 |
|---|---|
| 方法与路径 | `POST /auth/refresh` |
| 端与角色 | 运营端；持会话者 |
| 鉴权 | 凭 HttpOnly cookie 中的刷新令牌 |
| 请求 | 无请求体 |
| 响应 | 同 API-001，身份字段同样平铺为 `accountId` 与 `accountName` |
| 业务错误码 | `AUTH_1004`、`COMMON_9003` |
| 幂等 | 不做幂等：刷新令牌一次性使用；幂等键不适用、作用域不适用、窗口为令牌剩余有效期、同键异体不适用 |
| 关联不变量 | INV-19 |

约束：超时预算 3 秒；限流 30 次/分钟/账号；降级不适用；并发下同一刷新令牌只允许一个请求成功，其余 401；验收用例覆盖正常续期与重复使用。

### API-003 登出

| 项 | 内容 |
|---|---|
| 方法与路径 | `POST /auth/logout` |
| 端与角色 | 运营端；持会话者 |
| 鉴权 | 需访问令牌 |
| 请求 | 无请求体 |
| 响应 | `data` 为空 |
| 业务错误码 | `AUTH_1002`、`COMMON_9003` |
| 幂等 | 不做幂等：重复登出不产生副作用，但首次登出已把 `jti` 写入黑名单，第二次调用同一令牌返回 401 `AUTH_1002`；幂等键不适用、作用域不适用、窗口不适用、同键异体不适用 |
| 关联不变量 | INV-19 |

约束：超时预算 3 秒；限流 30 次/分钟/账号；降级时黑名单写失败以令牌有效期兜底；并发下重复登出不产生副作用；验收用例覆盖登出后旧令牌 401。

### API-004 当前账号

| 项 | 内容 |
|---|---|
| 方法与路径 | `GET /auth/me` |
| 端与角色 | 运营端；全部已登录角色 |
| 鉴权 | 需访问令牌 |
| 请求 | 无参数 |
| 响应 | `{id, accountName, roles[], permissions[], dataScope{allOwners, ownerIds[], allWarehouses, warehouseIds[], empty}}`：`id` 账号主键，十进制字符串，`accountName` 字符串，`roles[]` 角色编码，`permissions[]` 权限点编码；`dataScope` 两个维度各用「全部标记 + 标识列表」表达，`allOwners` 为 `true` 表示货主域是全部（此时 `ownerIds[]` 为空数组），为 `false` 表示只有 `ownerIds[]` 内的货主，仓域同构；`empty` 是派生标记，任一维度为「指定列表且列表为空」时为 `true`，正常授权下恒为 `false`，为 `true` 时调用方必须按无可见数据渲染，不得当作全部；`ownerIds[]` 与 `warehouseIds[]` 的元素同为十进制字符串 |
| 业务错误码 | `AUTH_1002`、`COMMON_9003` |
| 幂等 | 只读，无副作用，重复请求结果一致 |
| 关联不变量 | INV-19、INV-06 |

约束：超时预算 5 秒；限流 120 次/分钟/账号；降级时缓存不可用查库并标记数据时间戳；并发只读；验收用例覆盖权限为空（`permissions` 为空数组）、数据域为全部（两个 `all*` 均 `true` 且两个列表为空）、数据域为指定列表（`all*` 为 `false` 且列表非空）、`empty` 在正常授权下为 `false`。

### API-005 账号列表

| 项 | 内容 |
|---|---|
| 方法与路径 | `GET /accounts` |
| 端与角色 | 运营端；权限管理员、主数据管理员 |
| 鉴权 | `hub.account.read` |
| 请求 | 参数 `keyword`（字符串，按账号名模糊匹配）、`status`（取 `ENABLED`／`DISABLED`，缺省不过滤）、`page`（整数，从 1 起，缺省 1）、`size`（整数，1–100，缺省 20）；无域标识参数：本接口不经货主或仓寻址，越域没有可传入口（见错误码表后的说明） |
| 响应 | 分页结构，条目含 `id`（账号主键，十进制字符串）、`accountName`、`employeeId`（十进制字符串或 `null`）、`status`、`roles[]`（角色编码）、`version`（整数） |
| 业务错误码 | `AUTHZ_2001`、`COMMON_9001` |
| 幂等 | 只读，无副作用 |
| 关联不变量 | INV-06、INV-19 |

约束：超时预算 10 秒；限流 120 次/分钟/账号；降级查库并标记数据时间戳；并发只读；验收用例覆盖分页边界、空结果、停用账号仍出现在列表但登录返回 401、`status` 传闭集外取值返回 `COMMON_9001`。

### API-006 创建账号

| 项 | 内容 |
|---|---|
| 方法与路径 | `POST /accounts` |
| 端与角色 | 运营端；权限管理员 |
| 鉴权 | `hub.account.create` |
| 请求 | `{accountName, employeeId, initialPassword, roleIds[]}`：`accountName` 为字符串、必填、3–64 位、只允许字母数字与 `.` `_` `-`；`employeeId` 为**十进制字符串、必填**，取员工内部主键而非工号，传 `9102` 这类 JSON 数字返回 `COMMON_9001`；员工档案归单仓端（`19`、`20`），本变更不提供员工查询接口，该值由调用方从单仓端取得；`initialPassword` 为字符串、必填、非空、长度上限 72；`roleIds[]` 为角色主键十进制字符串数组，可空 |
| 响应 | 202，`data = {acceptNo}`；`acceptNo` 为字符串受理号，结果按 API-018 轮询 |
| 业务错误码 | `ACCOUNT_3001`、`ACCOUNT_3002`、`COMMON_9001`、`COMMON_9004`、`COMMON_9005` |
| 幂等 | 幂等键为请求头 `Idempotency-Key`；作用域为操作账号 + 接口 + 请求体指纹；窗口 10 分钟；重复请求返回同一受理号；同键异体返回 409 `COMMON_9005` |
| 关联不变量 | INV-19 |

约束：超时预算 5 秒（只到受理完成）；限流 60 次/分钟/账号；降级时受理表写失败直接 503 不返回受理号；并发冲突由唯一约束兜底并把第二次落为失败受理；验收用例覆盖受理号返回、同键重复、同键异体、账号重名、员工重复绑定、`employeeId` 传字符串返回 `COMMON_9001`。

### API-007 账号详情

| 项 | 内容 |
|---|---|
| 方法与路径 | `GET /accounts/{id}` |
| 端与角色 | 运营端；权限管理员、主数据管理员 |
| 鉴权 | `hub.account.read` |
| 请求 | 路径参数 `id` 为账号主键十进制字符串 |
| 响应 | `{id, accountName, employeeId, status, roles[], version}`：`id` 账号主键十进制字符串；`employeeId` 十进制字符串或 `null`，货主端账号不绑员工故为空；`status` 取 `ENABLED`／`DISABLED`；`roles[]` 角色编码；`version` 整数；不含密码 |
| 业务错误码 | `ACCOUNT_3003`、`AUTHZ_2001` |
| 幂等 | 只读，无副作用 |
| 关联不变量 | INV-06、INV-19 |

约束：超时预算 5 秒；限流 120 次/分钟/账号；降级查库并标记数据时间戳；并发只读；验收用例覆盖不存在返回 `ACCOUNT_3003`、`employeeId` 为字符串而非数字。

### API-008 修改账号

| 项 | 内容 |
|---|---|
| 方法与路径 | `PATCH /accounts/{id}` |
| 端与角色 | 运营端；权限管理员 |
| 鉴权 | `hub.account.update` |
| 请求 | 路径参数 `id` 为账号主键十进制字符串；请求体 `{status?, resetPassword?, version}`，`version` 为账号当前版本整数、必填：`status` 与 `resetPassword` 互斥，同时给出或都缺省返回 `COMMON_9001`；`status` 取 `ENABLED`／`DISABLED`，`resetPassword` 为非空字符串、长度上限 72。请求体不含 `employeeId`：员工绑定在建号时确定，之后不可改（见 `19`「七张表，权限点不在其中新建」） |
| 响应 | 202，`data = {acceptNo}` |
| 业务错误码 | `AUTHZ_2005`、`ACCOUNT_3003`、`COMMON_9001`、`COMMON_9004`、`COMMON_9005` |
| 幂等 | 幂等键为 `Idempotency-Key`；作用域为操作账号 + 接口 + 请求体指纹；窗口 10 分钟；重复请求返回同一受理号；同键异体返回 409 `COMMON_9005` |
| 关联不变量 | INV-19 |

约束：超时预算 5 秒；限流 60 次/分钟/账号；降级时受理表不可用 503；并发下停用与重置按版本号串行，落后方 `COMMON_9004`；验收用例覆盖停用后登录失败、重置后旧密码失效、停用最后一个权限管理员返回 `AUTHZ_2005`、请求体带 `employeeId` 时被忽略且不改变绑定。

### API-009 分配角色

| 项 | 内容 |
|---|---|
| 方法与路径 | `PUT /accounts/{id}/roles` |
| 端与角色 | 运营端；权限管理员 |
| 鉴权 | `hub.account.role` |
| 请求 | `{roleIds[], version}`，整体替换：`roleIds[]` 为角色主键十进制字符串数组、可空（空数组表示清空）；`version` 为账号当前版本整数、必填 |
| 响应 | 202，`data = {acceptNo}` |
| 业务错误码 | `AUTHZ_2004`、`ROLE_4002`、`COMMON_9004`、`COMMON_9005` |
| 幂等 | 幂等键为 `Idempotency-Key`；作用域为操作账号 + 接口 + 请求体指纹；窗口 10 分钟；重复请求返回同一受理号；同键异体返回 409 `COMMON_9005` |
| 关联不变量 | INV-19 |

约束：超时预算 5 秒；限流 60 次/分钟/账号；降级时受理表不可用 503；并发按 `version` 乐观锁，落后方 `COMMON_9004`；验收用例覆盖自授 `AUTHZ_2004`、跨端角色 `ROLE_4002`、回收后下一次请求 403。

### API-010 角色列表

| 项 | 内容 |
|---|---|
| 方法与路径 | `GET /roles` |
| 端与角色 | 运营端；权限管理员、主数据管理员 |
| 鉴权 | `hub.role.read` |
| 请求 | 参数 `client`（端标识，取枚举 `HUB`／`OWNER`／`SITE`／`MOBILE`，闭集外取值返回 `COMMON_9001`；缺省不过滤） |
| 响应 | `[{id, roleCode, roleName, client, status, version, permissionCodes[]}]`：`id` 角色主键十进制字符串，`roleCode` 字符串，`client` 同上枚举，`status` 取 `ENABLED`／`DISABLED`，`version` 整数，`permissionCodes[]` 为该角色已绑定的权限点编码、按编码升序。角色页与授权抽屉直接消费该字段，不再逐角色另查 |
| 业务错误码 | `AUTHZ_2001`、`COMMON_9001` |
| 幂等 | 只读，无副作用 |
| 关联不变量 | INV-19 |

约束：超时预算 5 秒；限流 120 次/分钟/账号；降级查库并标记数据时间戳；并发只读；验收用例覆盖按端过滤（本变更只有 `HUB` 角色）、`client` 传闭集外取值返回 `COMMON_9001`。

### API-011 创建角色

| 项 | 内容 |
|---|---|
| 方法与路径 | `POST /roles` |
| 端与角色 | 运营端；权限管理员 |
| 鉴权 | `hub.role.create` |
| 请求 | `{roleCode, roleName, client}`：`roleCode` 与 `roleName` 为字符串、必填；`client` 取枚举 `HUB`／`OWNER`／`SITE`／`MOBILE`，闭集外取值返回 `COMMON_9001`，本变更只建 `HUB` 角色 |
| 响应 | 202，`data = {acceptNo}` |
| 业务错误码 | `ROLE_4002`、`COMMON_9001`、`COMMON_9005` |
| 幂等 | 幂等键为 `Idempotency-Key`；作用域为操作账号 + 接口 + 请求体指纹；窗口 10 分钟；重复请求返回同一受理号；同键异体返回 409 `COMMON_9005` |
| 关联不变量 | INV-19 |

约束：超时预算 5 秒；限流 30 次/分钟/账号；降级时受理表不可用 503；并发下重复角色编码由唯一约束兜底；验收用例覆盖正常创建、重名、`client` 传闭集外取值返回 `COMMON_9001`。

### API-012 修改角色

| 项 | 内容 |
|---|---|
| 方法与路径 | `PATCH /roles/{id}` |
| 端与角色 | 运营端；权限管理员 |
| 鉴权 | `hub.role.update` |
| 请求 | 路径参数 `id` 为角色主键十进制字符串；请求体 `{roleName, status, version}`：`roleName` 字符串必填，`status` 取 `ENABLED`／`DISABLED`，`version` 为角色当前版本整数、必填 |
| 响应 | 202，`data = {acceptNo}` |
| 业务错误码 | `ROLE_4001`、`COMMON_9001`、`COMMON_9004`、`COMMON_9005` |
| 幂等 | 幂等键为 `Idempotency-Key`；作用域为操作账号 + 接口 + 请求体指纹；窗口 10 分钟；重复请求返回同一受理号；同键异体返回 409 `COMMON_9005` |
| 关联不变量 | INV-19 |

约束：超时预算 5 秒；限流 30 次/分钟/账号；降级时受理表不可用 503；并发按 `version` 乐观锁；验收用例覆盖停用角色后账号失去对应权限、`status` 传闭集外取值返回 `COMMON_9001`。

### API-013 分配权限点

| 项 | 内容 |
|---|---|
| 方法与路径 | `PUT /roles/{id}/permissions` |
| 端与角色 | 运营端；权限管理员 |
| 鉴权 | `hub.role.assign` |
| 请求 | `{permissionCodes[], version}`，整体替换：`permissionCodes[]` 为权限点编码字符串数组、可空（空数组表示清空）；`version` 为角色当前版本整数、必填 |
| 响应 | 202，`data = {acceptNo}` |
| 业务错误码 | `ROLE_4001`、`PERM_5001`、`COMMON_9004`、`COMMON_9005` |
| 幂等 | 幂等键为 `Idempotency-Key`；作用域为操作账号 + 接口 + 请求体指纹；窗口 10 分钟；重复请求返回同一受理号；同键异体返回 409 `COMMON_9005` |
| 关联不变量 | INV-19 |

约束：超时预算 5 秒；限流 30 次/分钟/账号；降级时受理表不可用 503；并发按 `version` 乐观锁；验收用例覆盖未知权限点 `PERM_5001`、整体替换后旧权限立即失效、同键异体返回 `COMMON_9005`。

### API-014 权限点清单

| 项 | 内容 |
|---|---|
| 方法与路径 | `GET /permissions` |
| 端与角色 | 运营端；权限管理员 |
| 鉴权 | `hub.role.read` |
| 请求 | 无参数 |
| 响应 | `[{module, moduleName, items[{code,name}]}]`，只含启用项 |
| 业务错误码 | `AUTHZ_2001` |
| 幂等 | 只读，无副作用 |
| 关联不变量 | INV-19 |

约束：超时预算 5 秒；限流 60 次/分钟/账号；降级查库并标记数据时间戳；并发只读；验收用例覆盖停用项不出现。

### API-015 查询数据域

| 项 | 内容 |
|---|---|
| 方法与路径 | `GET /roles/{id}/scopes` |
| 端与角色 | 运营端；权限管理员 |
| 鉴权 | `hub.role.scope` |
| 请求 | 路径参数 `id` 为角色主键十进制字符串 |
| 响应 | `[{dimension, scopeType, targetIds[]}]`：`dimension` 取 `OWNER`／`WAREHOUSE`（枚举 E-14），`scopeType` 取 `ALL`／`LIST`（枚举 E-13），`targetIds[]` 为十进制字符串数组；`ALL` 时 `targetIds[]` 为空数组，`LIST` 时至少一个元素；只返回已配置的维度，未配置的维度不出现在数组里，且按「全部」参与合并。后半句 `05` 与 `19` 尚未明写（`19` 只写「同维度并集、跨维度交集、任一维度为空拒绝」），本契约先定：未配置等于不收窄，运营端角色默认全域，不因缺配置而无法作业；需回写 `05`「数据域只有货主与仓两个维度」与 `19`「数据域合并规则在服务端算一次，不逐条查询算」两节 |
| 业务错误码 | `ROLE_4001`、`AUTHZ_2001` |
| 幂等 | 只读，无副作用 |
| 关联不变量 | INV-06 |

约束：超时预算 5 秒；限流 60 次/分钟/账号；降级查库并标记数据时间戳；并发只读；验收用例覆盖已配置维度逐条返回、未配置维度不出现在数组里（前端按全部展示）。

### API-016 配置数据域

| 项 | 内容 |
|---|---|
| 方法与路径 | `PUT /roles/{id}/scopes` |
| 端与角色 | 运营端；权限管理员 |
| 鉴权 | `hub.role.scope` |
| 请求 | `{scopes[{dimension, scopeType, targetIds[]}], version}`，整体替换：`dimension` 只接受 `OWNER`／`WAREHOUSE`，其他值（如 `ZONE`）返回 `SCOPE_6001`；`scopeType` 只接受 `ALL`／`LIST`，其他值（如 `SPECIFIED`）返回 `COMMON_9001`；`ALL` 必须不带 `targetIds[]`、`LIST` 必须至少一个元素，违反返回 `SCOPE_6002`；`targetIds[]` 为十进制字符串数组；`version` 为角色当前版本整数、必填 |
| 响应 | 202，`data = {acceptNo}` |
| 业务错误码 | `SCOPE_6001`、`SCOPE_6002`、`AUTHZ_2004`、`COMMON_9001`、`COMMON_9004`、`COMMON_9005` |
| 幂等 | 幂等键为 `Idempotency-Key`；作用域为操作账号 + 接口 + 请求体指纹；窗口 10 分钟；重复请求返回同一受理号；同键异体返回 409 `COMMON_9005` |
| 关联不变量 | INV-06、INV-19 |

约束：超时预算 5 秒；限流 30 次/分钟/账号；降级时受理表不可用 503；并发按 `version` 乐观锁；验收用例覆盖库区维度 `SCOPE_6001`、已归档仓 `SCOPE_6002`、`LIST` 空列表 `SCOPE_6002`、`scopeType` 传 `SPECIFIED` 返回 `COMMON_9001`、落后方 `COMMON_9004`。

### API-017 审计日志

| 项 | 内容 |
|---|---|
| 方法与路径 | `GET /audit-logs` |
| 端与角色 | 运营端；权限管理员，审计只读岗位复用该角色 |
| 鉴权 | `hub.audit.read` |
| 请求 | 参数 `targetAccountId`（账号主键十进制字符串）、`from` 与 `to`（UTC ISO-8601 时间，闭区间，缺省不限）、`page`（整数，从 1 起，缺省 1）、`size`（整数，1–100，缺省 20）；无域标识参数：同 API-005，越域没有可传入口 |
| 响应 | 分页结构，条目含 `id`（记录主键）、`operatorId`（操作者账号主键）、`targetAccountId`（目标账号主键）、`action`、`before`、`after`、`createdAt`；四个标识同为十进制字符串，`targetAccountId` 在非账号类操作时可为 `null` |
| 业务错误码 | `AUTHZ_2001` |
| 幂等 | 只读，无副作用 |
| 关联不变量 | INV-19 |

约束：超时预算 10 秒；限流 60 次/分钟/账号；降级查库并标记数据时间戳；并发只读；验收用例覆盖时间区间、目标账号过滤、空结果、无 `hub.audit.read` 时返回 `AUTHZ_2001`。

### API-018 受理结果查询

| 项 | 内容 |
|---|---|
| 方法与路径 | `GET /acceptances/{acceptNo}` |
| 端与角色 | 运营端；提交受理的账号本人 |
| 鉴权 | 需访问令牌，且只允许查本人受理单 |
| 请求 | 路径参数 `acceptNo` |
| 响应 | `{acceptNo, status, result, errorCode, createdAt, finishedAt}` |
| 业务错误码 | `AUTH_1002`、`COMMON_9003` |
| 幂等 | 只读，无副作用 |
| 关联不变量 | INV-19 |

约束：超时预算 3 秒；限流 300 次/分钟/账号（前端 3 秒轮询）；降级只读受理表，表不可用 503；并发只读；验收用例覆盖成功、失败、处理中、查不到四种返回。

### 鲁棒性约定

| 项 | 约定 |
|---|---|
| 超时 | 客户端单次请求 15 秒；服务端业务处理 5 秒；受理类接口只到受理完成为止 |
| 重试 | 读接口客户端可重试 2 次，间隔 200ms 与 600ms；写接口不自动重试，由用户按受理号重提 |
| 限流 | 登录 10 次/分钟/IP 与账号；写接口 30 至 60 次/分钟/账号；查询 120 次/分钟/账号；响应带 `Retry-After` |
| 入口拒绝 | 缺 `Idempotency-Key`、枚举闭集外取值、标识非十进制字符串、必填缺失一律在入口返回 400 `COMMON_9001`（`dimension` 闭集外取值返回 `SCOPE_6001`），不进入业务逻辑，不出 5xx |
| 批量与请求体 | 待确认：`roleIds[]`、`permissionCodes[]`、`scopes[]` 的条数上限与请求体字节上限未定，当前只受网关默认请求体大小约束；定稿后写入本节并同步部署配置 |
| 降级 | 权限缓存不可用降级查库；库不可用返回 503，不放行；降级数据在界面标注数据时间戳 |
| 传输 | 生产强制 HTTPS；刷新令牌 cookie 带 `Secure`、`HttpOnly`、`SameSite=Lax` |
| 追踪 | 所有响应带 `traceId`，与日志一致 |

### 幂等约定

| 接口类型 | 幂等键 | 作用域 | 窗口 | 并发重复请求的承诺 |
|---|---|---|---|---|
| API-006、API-008、API-009、API-011、API-012、API-013、API-016 | 请求头 `Idempotency-Key`（1–64 位可见字符，大小写敏感，推荐 UUID） | 操作账号 + 接口 + 请求体指纹 | 10 分钟 | 第二次请求返回首次受理号，不产生第二张受理单；同键异体返回 409 `COMMON_9005` |
| API-001、API-002、API-003 | 不做幂等 | — | — | 重复登录各自签发新令牌；刷新令牌一次性，重复使用 401；重复登出结果一致 |
| API-004、API-005、API-007、API-010、API-014、API-015、API-017、API-018 | 只读 | — | — | 无副作用，重复请求结果一致 |

缺少 `Idempotency-Key` 的创建与授权类请求直接返回 400 `COMMON_9001`，不允许「不带键也放行」。

五条口径：

1. **键的格式**：服务端只校验非空与长度 1–64，不解析业务含义。本变更的写对象（账号、角色、数据域）没有外部单号，键一律取客户端请求号（`00` D21）。
2. **请求指纹**：覆盖 HTTP 方法、路径、路径参数与请求体；不含 `Idempotency-Key` 本身，也不含仅影响传输的头部。同键且同指纹才算重复。
3. **同键异体**：必须按键索引取首次记录、再比对指纹，指纹不同即 409 `COMMON_9005`；不覆盖首次受理单、不新建受理单、不回退成新请求。若按键 + 指纹一起索引，异体请求会拿到新指纹而绕过去重。
4. **重复的响应**：与首次逐字段一致（同状态码、同受理号），不追加「是否重放」标记——受理号本身已是幂等证据，多一个布尔会让调用方写出两条分支。
5. **没有「处理中」这一对外状态**：受理单在本请求内同步写入，受理号在首次请求即确定，因此重复提交要么命中已有受理单（返回同一受理号），要么是全新请求；不设 409／425 的处理中错误码，也不要求调用方轮询重试。

### 错误码表

| 错误码 | HTTP | 重试 | 触发条件 | 出现位置 | 调用方处置 |
|---|---|---|---|---|---|
| `AUTH_1001` | 401 | 否 | 账号不存在或密码错误，不区分原因 | API-001 | 提示检查账号密码 |
| `AUTH_1002` | 401 | 是 | 令牌无效或过期 | 全部需鉴权接口 | 续期后重试，失败跳登录 |
| `AUTH_1003` | 401 | 否 | 账号已停用 | API-001、API-002 | 提示联系管理员 |
| `AUTH_1004` | 401 | 否 | 刷新令牌无效或已使用 | API-002 | 跳登录页 |
| `AUTHZ_2001` | 403 | 否 | 无接口所需权限点；运营端账号没有任何角色 | 全部需鉴权接口、API-001 | 显示无权限页；登录时提示联系管理员分配角色 |
| `AUTHZ_2004` | 403 | 否 | 操作者与目标账号相同 | API-009、API-016 | 提示不能给自己授权 |
| `AUTHZ_2005` | 409 | 否 | 变更后可用权限管理员为零 | API-008、API-009 | 提示至少保留一个 |
| `ACCOUNT_3001` | 409 | 否 | 账号名已存在 | API-006 | 换名重提 |
| `ACCOUNT_3002` | 409 | 否 | 该员工已有账号 | API-006 | 提示改用已有账号 |
| `ACCOUNT_3003` | 404 | 否 | 账号不存在 | API-007、API-008、API-009 | 刷新列表 |
| `ROLE_4001` | 404 | 否 | 角色不存在 | API-012、API-013、API-015、API-016 | 刷新列表 |
| `ROLE_4002` | 400 | 否 | 角色端标识与账号或请求不匹配 | API-009、API-011 | 改选本端角色 |
| `PERM_5001` | 400 | 否 | 提交了代码中不存在的权限点 | API-013 | 刷新清单后重提 |
| `SCOPE_6001` | 400 | 否 | 数据域维度只支持货主与仓，或取值不在 `OWNER`／`WAREHOUSE` 闭集 | API-016 | 改维度 |
| `SCOPE_6002` | 400 | 否 | `LIST` 未给标识、`ALL` 携带标识，或标识不存在／已归档 | API-016 | 重选有效标识 |
| `COMMON_9001` | 400 | 否 | 参数校验失败、缺少幂等键、标识非十进制字符串、枚举值不在闭集、互斥字段同时给出 | 全部写接口、API-005、API-010 | 按 `message` 提示修正请求参数 |
| `COMMON_9002` | 429 | 是 | 请求过于频繁 | API-001、写接口 | 按 `Retry-After` 等待 |
| `COMMON_9003` | 503 | 是 | 权限或存储服务不可用 | 全部 | 稍后重试，请求不变 |
| `COMMON_9004` | 409 | 否 | 版本冲突 | API-009、API-012、API-013、API-016 | 刷新后重提 |
| `COMMON_9005` | 409 | 否 | 同一 `Idempotency-Key` 已受理过，但本次请求体指纹与首次不同 | API-006、API-008、API-009、API-011、API-012、API-013、API-016 | 恢复首次请求体用原键重提，或换新键当新请求 |

两个码不在本章登记：`AUTHZ_2002`（数据域为空）与 `AUTHZ_2003`（请求标识超出会话数据域）。理由是运营端的会话固定域只有端标识（`19`「三个端的固定域在登录时写入会话」），配置域未配置的维度按「全部」参与合并（语义见 API-015），`LIST` 加空列表在写入时已被 `SCOPE_6002` 拒绝；本变更的十八个接口既不按货主或仓寻址，也构造不出空域，两个码实测均不可达（见 `verification.md` 的 6.4）。`19` 的第 3 道门在运营端仍然生效（合并结果为空即拒绝），只是本变更没有走到该门的接口；等出现按货主或按仓寻址的查询接口时，连同可传的域标识参数一起重新登记，两个编号保留给该语义，不复用于其他错误。

### 契约验收

1. 十八个接口在 `contracts/openapi.json` 均有定义，导出物与提交物一致。
2. 每个写接口在幂等约定表中有对应行，创建与授权类接口缺 `Idempotency-Key` 时返回 400 `COMMON_9001`；同键换请求体重提返回 409 `COMMON_9005`，首次受理单与首次响应不被覆盖。
3. 错误码表中每个码至少一条联调用例；401 由 `AUTH_1002`、403 由 `AUTHZ_2001` 与 `AUTHZ_2004`、409 由 `ACCOUNT_3001` 与 `COMMON_9004`、429 由 `COMMON_9002`、503 由 `COMMON_9003` 各覆盖一次。
4. 前端只消费生成类型，仓库内检索不到手写的请求体定义。
5. 鉴权与参数校验测试覆盖五种情形：无令牌、过期令牌、无权限点（`AUTHZ_2001`）、数据域摘要的两种形状（全部与指定列表）、`employeeId` 传字符串返回 `COMMON_9001`。
6. 页面映射表中的每个接口编号都能在契约章找到同名条目。
7. 枚举闭集：`scopeType` 传 `SPECIFIED`、`status` 与 `client` 传闭集外取值均返回 400 `COMMON_9001`，不落库、不出 5xx；`dimension` 传闭集外取值返回 400 `SCOPE_6001`。
8. 响应形状与契约逐字段一致：API-001／API-002 的 `accountId` 与 `accountName` 平铺，API-004 的 `dataScope` 为 `allOwners`／`ownerIds`／`allWarehouses`／`warehouseIds`／`empty` 五个字段，API-007 的 `employeeId` 为十进制字符串或 `null`。

## 风险与权衡

| 风险 | 缓解 |
|---|---|
| 全局版本号导致改一个人权限让全员下次请求多查一次库 | 权限变更频率低，重建只影响下一次请求 |
| 数据域显式传参依赖开发者自觉 | 列表查询方法签名强制带 `DataScope`，测试断言无该参数的查询方法数为零 |
| 启动校验让遗漏权限点的新接口直接起不来 | 有意设计，开发期本地即暴露 |
| 受理环让配置操作看起来「变慢」 | 前端 3 秒轮询；受理到结果通常在 1 秒内，用户看到的是「已受理」 |
| 权限点编码发布后不可复用 | 编码只增不改，下线功能置停用 |

## 迁移与回滚

部署顺序：执行 `wms-hub` 的 Flyway 迁移与角色脚本 → 发布后端 → 发布前端。回滚只回滚应用版本，不回滚已执行迁移（`11` 规定脚本只增不改）。前端单独回滚不影响后端：旧版前端不认识的权限点按「无权限」隐藏入口，不会越权。

## 待确认

- 会话超时时长未定（`12` 的 `UI-X05`）：本设计取访问令牌 30 分钟、刷新 7 天，定稿后只改配置。
- 品牌主色未定：令牌入口已收敛到单一变量，定稿后只改一处。
