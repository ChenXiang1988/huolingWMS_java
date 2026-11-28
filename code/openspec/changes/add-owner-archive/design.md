# Design

## Context

本次变更的需求源头是 `proposal.md` 与 `specs/owner-archive/spec.md`（八条要求、二十条场景）。当前有效版本已经把货主档案登记为主数据的一部分，但代码与契约都没有落地：

- `24-分端功能清单.md` 第 3.3 节登记了运营端「货主建档、改档、停用」（骨架 `UI-P01` + `UI-P03`，权限点模块 M01 货主，角色主数据管理员），页面与接口都不存在。
- `05-角色与权限矩阵.md` 第 99–107 行把货主端角色限定为「商品档案可写，其余只读」，第 205–223 行把「主数据」列的写能力给主数据管理员、读能力给运营端其余角色，货主档案读写的权限点尚未登记。
- `19-权限系统设计.md` 第 47 行规定账号记录含所属货主（仅货主端账号有值），货主端账号的归属货主目前是一个没有主数据约束的裸数字。
- `22-状态机与事务边界总表.md` 的八类对象里没有货主档案，`21-术语与枚举登记表.md` 没有货主档案状态枚举。
- 契约与代码现状：`code/contracts/openapi.json` 已登记商品档案等既有接口（编号见该变更契约章，本变更新增编号自 API-021 起）；`GET /api/hub/owners`（API-020）当前从货主端账号反向派生展示名，权限点为 `archive.hub.create`，自身已注明「主数据表落地后替换投影」；既有档案错误码与受理机制（202 + 受理号 + 受理查询兜底）可复用。

约束来源：`00-决策字典.md`（D02、D03、D12、D15、D17、D18、D19、D20、D21、D33、D40）、`00a-红线与不变量.md`（INV-06、INV-18、INV-19）、`12a-网页页面与分端规格.md`（`UI-P01` 列表页、`UI-P03` 表单页、`UI-F01` 至 `UI-F03` 异步受理节奏）。

## Goals / Non-Goals

**Goals:**

- 把货主档案作为权威主数据落地：系统生成且不可变的货主编号、四项必填、信用代码全局唯一、可用与停用两态。
- 运营端获得查询、建档、改档、停用与重新启用五个能力，鉴权与数据域对齐 `05` 与 `19`。
- 关闭 API-020 的契约缺口：选择器改读货主档案，只返回可用货主，提供货主编号与展示名两个可检索字段。
- 用一套契约把「停用阻断新建、不阻断存量、不自动恢复、账号绑定与登录拒绝」写成调用方可观察的承诺。

**Non-Goals:**

- 不定义合同与费率卡绑定，不做算费与出账（`D33` 的准入判定仍归计费域，本契约不表达合同有效性）。
- 不提供批量建档、批量停用、导入导出与开放接口建货主；不提供货主自助注册。
- 不提供单仓端或货主端的货主档案接口；不做仓级授权（`D40`）。
- 不新增下行事件与回调订阅，不设计货主档案的表结构、索引与事务划分（属后端设计章）。
- 不改动 `00-决策字典.md`、`00a-红线与不变量.md` 的既有结论。

## Decisions

| 决策 | 选择 | 理由与备选 |
|---|---|---|
| 写接口是否异步受理 | 沿用既有 **202 受理**：写接口立即返回 `acceptNo` 与受理时状态快照，执行结果由新增的受理查询接口（API-027）兜底 | `00` D20「写入一律异步受理」与 `12a` `UI-F01`（写入不得显示成功、必须展示受理号）都是既有约束，本代理无权改 `00`；货主档案虽无 Site 下行，仍按同一节奏落地以保持三端一致。备选「同步返回」会使本契约违反 D20 与 `UI-F01`，若要改必须先在 `00` 与 `12a` 立项修订 |
| 业务校验的时点 | 入口同步只做字段级校验（`PARAM_INVALID`）；存在性、状态守卫、版本比对、信用代码唯一性一律在受理后判定，失败经 API-027 的 `errorCode` 返回 | 与商品档案既有受理模型一致（入口校验与业务动作分离），避免两套写路径行为不同；代价是业务失败需轮询一次才能看到 |
| 选择器路径是否变化 | **不变**，仍为 `GET /api/hub/owners`（API-020 沿用原号改造） | 变的只是数据来源，资源语义（运营端可选的货主目录）未变；把「选择器」这种用途做成路径段会让路径维度从「资源」漂移到「页面用途」。备选 `GET /api/hub/owner-archives/selector` 被否决 |
| 选择器权限点 | 由 `archive.hub.create` 改为 `owner.hub.read` | 选择器的新用途是「账号开通时指定货主」，`05` 给权限管理员的是「主数据读」，保留原权限点会让权限管理员无法开通货主端账号，直接违反本次需求。备选「新增一枚选择器专用点」被否决：同一资源两种读权限会漂移 |
| 对外标识 | 一律用 `ownerNo` 寻址（路径参数）与检索；`ownerId` 仅作为既有契约键（商品档案代建请求、账号开通）透传，不用于寻址 | 货主编号不可变、可分享、可打给客户；内部主键进路径会让「换库重导」变成破坏性变更 |
| 货主编号形态 | 建议 `OW` 加 6 位十进制序号（首个 `OW000001`）；契约只对调用方承诺「以 `OW` 开头、8 至 32 位、字符集 `[A-Z0-9]`、全局唯一、不可变」，调用方 MUST NOT 解析或依赖固定位数 | 位数增长（超过六位序号）不构成破坏性变更；备选「对外不承诺任何形态」会让前端无法给出输入提示 |
| 信用代码校验深度 | 只校验 18 位与字符集（大写字母排除 `I`、`O`、`S`、`V`、`Z`，加数字），**不校验**校验位；小写提交即 `PARAM_INVALID` | 校验位算法未在 `00`、`00a`、`21` 中登记，自行加严会把存量主体误拒；大小写不静默转写，避免「同一主体两种写法绕过唯一性」 |
| 停用与启用是否带版本号 | **必带** `version`，与商品档案停用接口一致 | 防止「改档与停用交错」造成静默覆盖；已有状态守卫只能挡住重复迁移，挡不住跨动作丢更新 |
| 受理查询接口 | 新增 API-027 `GET /api/hub/owner-archives/acceptances/{acceptNo}`，权限点 `owner.hub.read` | 复用商品档案的受理查询接口会强迫调用方持商品档案读权限（`archive.hub.read`），权限点与业务对象错配；受理存储仍可复用，契约层各自一条 |
| 数据域 | 货主档案接口按会话的**货主域**收窄：域为「全部」（含未配置）看全量；域为「指定列表」只看域内，域外按 `NOT_FOUND_OWNER` 处理；指定列表为空直接 `FORBIDDEN_OWNER`；建档要求域为「全部」 | 依据 `19`「一次请求过四道门」：接口一旦带域标识参数，第三道门立即生效；依据 `05`「数据域为空不得放行，不回落成全部」 |
| 停用对作业受理的阻断 | 只写成调用方可观察的承诺，不在本契约新增接口 | 「以该货主为对象的新建业务被拒」发生在作业受理链路（单据域），接口与错误码归该域契约；本契约不定义 Site 侧是否缓存货主状态（见待确认登记） |
| 名称重复 | 允许同名，不做模糊去重；契约不含任何「名称唯一性」承诺 | 已定口径：靠货主编号区分 |

## 前端设计

本章只写运营端 Web 的页面、路由、接口映射、权限入口与状态归属。交互与界面一律引用本次 `ui.md` 的 `OA-01`~`OA-10` 与 `12-界面与交互设计规范.md`、`12a-网页页面与分端规格.md` 的 `UI-*`，本章不重写步骤、分支、文案与视觉；接口编号只取 `## API 接口契约` 章的 API-020 至 API-027，契约没有的字段一律登记在「待确认与契约缺口」，不假设存在。前端只做展示与格式校验，功能权限与数据域以服务端判定为准（`UI-I11`）；写入只接受受理票据，结果经 API-027 到达（D20）。

### 页面清单与路由

| 端 | 路由 | 页面职责 | 主要角色 | 骨架 | 新增或改造 | 首期 | 依赖接口编号 |
|---|---|---|---|---|---|---|---|
| 运营端 | `/hub/owner-archives` | 货主档案查询与翻页、打开详情抽屉、发起停用与启用 | 六个只读角色均可查；停用与启用仅主数据管理员 | `UI-P01` | 新增 | 是 | API-021、API-022、API-025、API-026、API-027 |
| 运营端 | `/hub/owner-archives/new` | 建档表单，提交后进受理环 | 主数据管理员 | `UI-P03` | 新增 | 是 | API-023、API-027 |
| 运营端 | `/hub/owner-archives/:ownerNo/edit` | 改档表单，回填最新详情并带 `version` 提交 | 主数据管理员 | `UI-P03` | 新增 | 是 | API-022、API-024、API-027 |
| 运营端 | `/accounts` | 账号管理：新建抽屉内选「端」与归属货主；列表与详情展示所属货主 | 权限管理员 | `UI-P01`（既有） | 改造 | 是 | API-020（归属货主选择器）；账号开通与受理查询为契约 1.7 登记的既有接口，无 API 编号 |

三条不计入页面清单的界面：

- **货主档案详情抽屉**：不单列独立路由，由列表页按 `UI-D01` 在当前页打开，复用 `DetailDrawer`，见「组件装配」。
- **停用与启用确认弹窗**：`UI-C05` 弹窗，不是页面；按 `UI-D09` 先关抽屉再开弹窗，弹窗由列表页承载（`OA-05`、`OA-06`）。
- **无权限页**：沿用既有 `/forbidden` 与 `ForbiddenView`（`UI-S03`），本变更新增的是它带上的权限点值，不新增页面。

本变更不新增 `UI-P02`、`UI-P09`、`UI-P10` 页面：货主档案没有整页详情（无独立分享或打印需求）、没有左右树配置、没有打印件。

**路由元数据**（路由表落 `code/web/apps/admin/src/router/index.ts`，菜单成员由 `AdminLayout` 按「路由表 ∩ 权限集」算出）：

| 路由 | name | meta 关键项 | 说明 |
|---|---|---|---|
| `/hub/owner-archives` | `hub-owner-archives` | `title: '货主档案'`、`menu: true`、`permission: PERMISSIONS.ownerHubRead`、`group: '主数据'`、`icon: 'OfficeBuilding'` | 分组落在既有「主数据」（`UI-P13` 固定顺序「概览、主数据、权限与审计」），与 `/hub/archives` 同组紧邻；图标名需在 `router/navigation.ts` 的 `ICONS` 登记一项，未登记按只显示文字处理（`UI-P13`） |
| `/hub/owner-archives/new` | `hub-owner-archive-create` | `title: '新建货主档案'`、`permission: PERMISSIONS.ownerHubWrite` | 不写 `menu`，不进菜单；守卫按 `canVisit` 拦截，直达无权限时落 `ForbiddenView`（`UI-S03`） |
| `/hub/owner-archives/:ownerNo/edit` | `hub-owner-archive-edit` | `title: '编辑货主档案'`、`permission: PERMISSIONS.ownerHubWrite`、`breadcrumb: ['货主档案', '编辑货主档案']` | 同上；`ownerNo` 作路径参数，与 API-022、API-024 的寻址口径一致（契约「对外标识一律用 `ownerNo` 寻址」） |
| `/accounts` | `accounts`（既有） | 既有 `permission: PERMISSIONS.accountRead` 不变 | 本次不新增路由、不改权限点，只改页面内部（见「组件装配」与「表单与校验」） |

进菜单的条件是**功能权限**：持有 `owner.hub.read` 的账号看到「货主档案」菜单项；数据域为空或为指定列表的账号菜单照常出现，进入后由服务端 403 决定表现（`UI-X06` 的第二种情况，见「菜单与权限点」）。

### 页面与接口映射表

| 页面 | 触发时机 | 接口编号 | 用途 | 失败表现 |
|---|---|---|---|---|
| 货主档案列表 | 进入页面、点「查询」、改筛选、点表头排序 | API-021 | 按 `ownerNo`（精确）、`ownerName`（模糊）、`creditCode`（精确）、`status`、`updatedFrom`、`updatedTo` 查询，`sortBy` 取 `ownerNo`/`ownerName`/`updateTime` | 区域内错误块 + 「重试」，筛选、排序与游标保留（`UI-S04`、`UI-X04`）；不得显示成空列表（契约 1.2「降级」） |
| 货主档案列表 | 点「下一页」/「上一页」、改每页条数 | API-021 | 键集分页：`cursor` 前进或回退，`limit` 取 20/50/100 | 同上；翻页失败停在当前页并保留游标 |
| 货主档案列表 | 行点击、货主编号点击、行内「详情」 | API-022 | 取详情开抽屉（列表缺 `lastStatusChange` 与 `createdBy`/`updatedBy`，不能只用行数据） | 抽屉内分区错误块 + 「重试」（`UI-S04`、`UI-D11`）；`NOT_FOUND_OWNER` 显示「不存在或不在数据域内」+「关闭」（`OA-02`） |
| 货主档案列表 | 详情抽屉「停用」→ 弹窗确认提交 | API-025 | 路径参数 `ownerNo`，体 `{reason, version}` | 同步错误就地提示；受理失败按 `OA-10` 分派（`UI-F03`、`UI-S04`） |
| 货主档案列表 | 详情抽屉「启用」→ 弹窗确认提交 | API-026 | 路径参数 `ownerNo`，体 `{reason, version}` | 同上；`STATE_INVALID`（已可用）第三段与出口按 `OA-06` |
| 货主档案列表、新建页、编辑页 | 拿到 202 受理票据后 1 秒首发、此后每 3 秒 | API-027 | 查询受理状态与结果摘要（`ownerId`/`ownerNo`/`status`/`version`） | 超时转「稍后查看」并保留受理号；404 停轮询；503 保留受理号（`OA-09`） |
| 新建货主档案 | 点「提交」 | API-023 | 体四项 | 同步 `PARAM_INVALID` 按 `details[].field` 就地定位（`UI-C03`）；受理失败按 `OA-10` |
| 新建货主档案 | 受理成功后点「查看详情」 | API-022 | 携 `?detail=<ownerNo>` 回列表并打开该货主抽屉 | 同详情抽屉 |
| 编辑货主档案 | 进入页面（或冲突后点「刷新查看」） | API-022 | 回填四项与 `version`；**每次进入重新取版本**，不复用上次的值（`OA-04`） | 骨架屏 → 错误块 + 「重试」（`UI-S01`、`UI-S04`）；停用态直达时顶部警告条并置灰提交（`OA-04`） |
| 编辑货主档案 | 点「提交」 | API-024 | 路径参数 `ownerNo`，体四项 + `version` | 版本冲突按 `OA-10` 两出口（`UI-X12`）；其余同新建 |
| 账号管理 | 新建抽屉把「端」选为货主端后检索货主 | API-020 | 按 `ownerNo` 精确或 `ownerName` 模糊远程检索，只取可用货主 | 选择器内 `UI-S04`「货主检索失败 + 错误码 + 重试」；`AUTHZ_2001` 给无权检索提示（`OA-07`） |
账号页改造涉及的三个动作不引用本契约编号，其接口属身份模块的既有契约，本变更只做字段扩展，编号与结构以该契约补齐后为准（见待确认第 1、2 条）：

- 新建抽屉「提交」：调用身份模块的账号创建接口，请求体需补「端」与归属货主字段；受理后按该模块的受理查询轮询，失败三分支同 `OA-09`，400 就地提示并保留已选值（`OA-07`）。
- 已绑定账号的详情与编辑：依赖账号响应返回端与归属货主字段，只读展示「所属货主」；无字段时显示「—」，不推断（`OA-08`、`UI-I11`）。

### 菜单与权限点

权限点取值只有两枚，逐字取契约 E-20：`owner.hub.read`、`owner.hub.write`。`05-角色与权限矩阵.md` 目前只到模块级、「主数据」列给出读六个角色与写一个角色，**权限点编码尚未登记**，本章提出的映射须并入权限矩阵（`21` 的 E-20 已建议登记，本表与之一致）。

| 菜单或按钮 | 位置 | 权限点 | `permissions.ts` 键名 | 角色（对齐 `05` 第 190–203 行） | 未命中表现 | 备注 |
|---|---|---|---|---|---|---|
| 「货主档案」菜单 | 侧栏「主数据」组 | `owner.hub.read` | `PERMISSIONS.ownerHubRead` | 主数据管理员、仓网管理员、权限管理员、计费专员、对账员、跨仓调度员（「主数据」列为读的六个角色） | 菜单项不渲染、不置灰（`UI-X06`、`UI-P13`） | 待并入权限矩阵 |
| 列表页表头排序、筛选、翻页 | 列表页 | `owner.hub.read` | `ownerHubRead`（同路由，不额外判定） | 同上 | 页面已由路由守卫拦截，页内不再二次判定 | — |
| 详情抽屉「编辑」 | 列表页抽屉底部 | `owner.hub.write` | `PERMISSIONS.ownerHubWrite` | 主数据管理员（「主数据」列为写） | 按钮不渲染（`UI-X06`） | 待并入权限矩阵 |
| 详情抽屉「停用」/「启用」 | 列表页抽屉底部 | `owner.hub.write` | `ownerHubWrite` | 主数据管理员 | 按钮不渲染 | 待并入权限矩阵 |
| 页头「新建」 | 列表页 | `owner.hub.write` | `ownerHubWrite` | 主数据管理员 | 不渲染，并在原位给一句说明（`OA-03`：非全域账号本不能建档） | 见下条「展示性判断」 |
| 表单页「提交」 | 新建、编辑 | `owner.hub.write` | `ownerHubWrite`（路由级，直达被守卫拦） | 主数据管理员 | 由路由守卫落 `/forbidden`（`UI-S03`） | 页内不再判定权限点 |
| 账号新建抽屉「端」单选与归属货主选择器 | 账号页 | `owner.hub.read` | `ownerHubRead` | 权限管理员（`05` 第 203 行账号写归权限管理员；契约 API-020 已把选择器权限点改为 `owner.hub.read`，否则权限管理员开不出货主端账号） | 选择器加载失败并提示「无权检索货主，请联系权限管理员（AUTHZ_2001）」，提交保持置灰（`OA-07`） | 待并入权限矩阵；服务端脚本已对持有 `archive.hub.create` 的角色补授该点 |

两条显示规则（都只影响展示，不构成准入）：

- **功能权限**：无权限点则入口不渲染，绝不置灰（`UI-X06` 第一行）；数据域不是功能权限，不据此隐藏菜单。
- **建档入口的展示性判断**：非「全部」货主域的账号本不能建档（契约 API-023「要求域为全部」），`OA-03` 要求「新建」不渲染并给一句说明，页面读会话里的 `auth.profile.dataScope.allOwners` 只用于隐藏入口与给文案；真正的准入仍由服务端 `FORBIDDEN_OWNER` 决定，页面不得据此放行任何请求（`UI-I11`）。

无数据域权限时的页面表现：菜单照常出现，进入列表后 API-021 返回 403 `FORBIDDEN_OWNER`，整页按 `UI-S03` 显示「无权访问」与需联系的角色（`PERMISSION_CONTACT_ROLE` 现状为「权限管理员」），出口「返回上一页」；详情抽屉内的域外目标按 `NOT_FOUND_OWNER` 处理（与不存在同一响应，`OA-02`）。前端 MUST NOT 把 403 显示成空列表，也不把「域为空」当作「看全部」（契约 1.2、`05` 第 3 节）。

### 组件装配

骨架一律取 12a 的 `UI-P01` 与 `UI-P03`，控件取 12 的 `UI-C`、`UI-B` 既有编号，本次不新造基础控件、不自写抽屉与字段皮肤（`UI-D04`、`UI-C31`）。

| 界面 | 骨架 | 复用的共享组件（`@wms/design`） | 本次装配要点 | 跨页复用 |
|---|---|---|---|---|
| 货主档案列表 | `UI-P01` | `PageHeader`（标题 + 页头主操作）、`FilterBar`（含折叠，`UI-C02`、`UI-C12`）、`DataTable`（键集模式：`hasMore`/`hasPrev`）、`StatusTag`（`UI-C06` 圆点 + 文字）、`AcceptPanel`、`ActionButton` | 筛选常显 4 项、第 5 项「信用代码」进折叠区（`OA-01`）；排序走表头三列的 `sortable="custom"` + `@sort-change` 写地址栏，白名单外的列不挂排序图标；分页用不带总数的键集形态 | `FilterBar`、`DataTable`、`StatusTag`、`AcceptPanel` 与商品档案页、账号页同源 |
| 货主档案详情抽屉 | `UI-C04` + `UI-D01`~`UI-D13` | `DetailDrawer`（唯一外壳）、`StatusTag`、`ActionButton` | 传参固定为 `title` = 货主名称、`subtitle` =「货主编号 OW…」、`status`/`statusType`、`sections` = 基础信息 → 状态变更 → 操作记录三段、`columns: 2`、`width: 880`、底部插槽（可用态「停用」+「编辑」，停用态「编辑」置灰给原因 +「启用」）；`closable` 在提交中置灰保留（`UI-D12`） | 外壳与商品档案页、账号页是同一组件；页面只传数据与分区，不新增外壳 |
| 新建货主档案 | `UI-P03` | `PageHeader`、`FormSection`（单组「货主信息」）、`FormField`（`help` 承载帮助文案）、`FormInput`、`ActionButton`（底部操作条）、`AcceptPanel` | 四项单列、控件高 32、间距 16（`UI-C03`）；帮助文案走 `FormField.help`，错误走 `FormField.error`（有错误时不显示帮助）；受理条落在页头下方、表单上方（`OA-09`） | 表单外壳与编辑页共用同一装配，差异只在标题、`description` 与提交接口 |
| 编辑货主档案 | `UI-P03` | 同新建 + `PageHeader.description` 承载只读货主编号 | 页头副标题为「货主编号 OW…」（只读，不进表单字段）；版本号不作为字段展示，只在提交时带上；冲突面板就地渲染在表单顶部（`OA-10`、`UI-X12`） | 与新建共用字段装配与校验规则，不复制一套 |
| 停用 / 启用确认弹窗 | `UI-C05` | 共享弹窗外壳 + `ActionButton`（主按钮用动作词，停用取危险色 `UI-T04`、启用取主按钮 `UI-T01`）、`FormField` | 内容顺序为对象行 → 影响清单 → 原因文本域（3 行、`maxlength` 200、字数提示）；由列表页打开，抽屉先关（`UI-D09`），提交中禁用关闭与遮罩（`UI-D12`） | 停用与启用共用同一个弹窗组件，只换动作词、影响清单与接口 |
| 账号页新建抽屉（改造） | `UI-C31`（既有宽 480） | 既有 `ActionDrawer` + `el-radio-group`（「端」单选）+ 货主选择器（`UI-B01`、`UI-B02`）+ `FormField` | 选「货主端」后才出现归属货主；选择器触发器为输入框样式、远程搜索防抖 300ms、弹层内为搜索框 + 结果表（货主名称 min 180、货主编号 160）+ 加载更多；上下键移动、Enter 选中、Esc 关闭（`OA-07`） | 选择器与代建页（`ArchivesView`）共用同一外壳 |
| 账号列表与详情（改造） | `UI-C01`、`UI-D05` | 既有 `DataTable`、`DetailDrawer` | 列表加「所属货主」列（min 180，「名称（编号）」，非货主端账号显示「—」）；详情「基本信息」加只读字段（`UI-T25` 只读皮肤）+ 一句不可变更说明（`OA-08`） | 复用账号页既有外壳，不新增抽屉 |

两处需要落在组件层（**页面不各自实现**，也不新造第六种骨架）：

- **货主选择器收进 `@wms/design`**：`UI-B01` 要求「货主、SKU、库位、批次、单据五类选择器共用同一结构」，当前实现内联在 `ArchivesView.vue` 的远程 `el-select` 里。本变更把它抽成共享选择器组件，代建页与账号页共用；同时按 API-020 的 **BREAKING** 改造把查询参数由 `ownerCode`/`name` 换成 `ownerNo`/`ownerName`、在条目上补 `ownerNo`（见「待确认与契约缺口」第 5 条：既有代建页必须同批改造，否则传入旧参数即 `PARAM_INVALID`）。
- **`UI-C05` 弹窗外壳**：`@wms/design` 目前只有抽屉外壳（`DetailDrawer`、`ActionDrawer`），没有承载「对象行 + 影响清单 + 原因文本域」的弹窗外壳。按 `UI-C05` 落一个共享弹窗组件，停用与启用共用；不复用 `ActionDrawer`（非抽屉），也不在页面自写 `el-dialog` 字段皮肤。

`DetailDrawer` 的复用边界写死三条：列表页详情一律走抽屉、不新增独立详情路由（`UI-D01`、`UI-P02`）；抽屉内不套抽屉与弹窗（`UI-D09`）；外壳不满足需求时改组件、不改页面（`UI-D13`）。本次三段分区恰为 3 段，分段目录常驻（`UI-D02`），不因分区数量隐藏。

### 状态与数据归属

| 状态 | 放哪 | 理由 |
|---|---|---|
| 筛选条件（`ownerNo`、`ownerName`、`status`、`updatedFrom`、`updatedTo`、`creditCode`）、折叠态 | URL | 可分享、可回退、刷新可还原（`UI-X04`、`UI-C02`）；沿用 `composables/useListQuery.ts`，不另写一套 |
| 排序（`sortBy`、`sortDir`）、每页条数（`limit`）、游标（`cursor`） | URL | 同上；`limit` 属分页状态，与游标一起进出地址栏 |
| 抽屉定位参数 `detail=<ownerNo>` | URL | 只由「建档成功的『查看详情』」写入，进入后按 API-022 开抽屉；关闭抽屉时 `router.replace` 去掉该参数。`detail` **不参与**列表查询（不是筛选参数），避免污染筛选条件 |
| 「上一页」游标栈 | 组件状态（`useListQuery` 现状） | 会话内一次性使用；`UI-X10` 的「页码」要求由 URL 里的 `cursor` 满足，不为跨页返回重建栈 |
| 列表条目、`hasMore`、`nextCursor`、加载与错误态 | 组件状态 | 服务端数据按需请求，不做全量本地缓存 |
| 抽屉详情（API-022 响应） | 组件状态 | 关闭即丢；列表行数据不足以渲染三段（缺 `lastStatusChange` 与创建/修改人），必须按需请求 |
| 停用/启用弹窗的开合、原因文本、`acceptNo` | 组件状态（列表页） | 只在当前页有效；受理条不跨页（见下条） |
| 受理号与受理条状态 | 组件状态（页面持有 `acceptNo`，状态由 `AcceptPanel` 内部维护） | 同一页面只保留最近一次受理条（`OA-09`）；不写 URL、不入 store |
| 表单四项输入、字段级错误、`submitting`、幂等键 | 组件状态（表单页） | 页面级草稿；编辑页的 `version` 每次进入重新取（`OA-04`），不缓存 |
| 货主选择器候选、搜索中、搜索失败 | 组件状态（`UI-B01` 组件内部） | 远程搜索结果必须新鲜且只含可用货主，缓存会显示已停用货主 |
| 会话身份、权限集、数据域 | Pinia `stores/auth.ts`（既有） | 跨页共享；只用于菜单渲染与建档入口的展示性判断，不作为准入 |

刷新与回退后的恢复行为：

- **刷新（F5）**：从 URL 重放筛选、排序、`limit`、`cursor`、折叠态并重新请求 API-021；`detail` 有值时再取 API-022 开抽屉。`OA-01` 的「首屏 20 行、无选中、筛选为空」只在无参数进入时成立。
- **浏览器回退**：地址栏写回用 `router.replace`（`useListQuery` 现状），筛选变化不新增历史条目，后退即离开列表页；再次进入列表页按 URL 初始化。
- **从表单页返回列表**：表单页的 query 携带 `from`（列表当时的完整路径含筛选与游标），「取消」与提交成功后按 `from` 回跳，落实 `UI-X10` 的筛选、页码与排序还原；无 `from`（深链接进入）回 `/hub/owner-archives` 无参数形态。滚动位置本期不实现（`UI-X10` 要求「至少前三项」）。
- **上一页栈**：从表单页返回后为空，只能向后翻；`DataTable` 的「上一页」按 `hasPrev` 置灰，不伪造可回退状态。
- **受理号不跨页**：离开页面即停止轮询并丢弃受理条（`AcceptPanel` 卸载停表），`OA-09` 要求保留的受理号只在当前页内有效；跨页找回入口本期不提供，见待确认第 4 条。

### 交互落地

只登记落点，不重写步骤、分支与反馈文案；步骤、文案与视觉以 `ui.md` 与 `12` 号规范原文为准。

| 交互条目 | 落在哪些页面或组件 | 触发接口编号 | 状态放哪 |
|---|---|---|---|
| `OA-01` 列表查询与翻页 | 货主档案列表（列表页整体） | API-021 | 筛选、排序、`limit`、`cursor`、折叠态在 URL；条目与加载/错误在组件 |
| `OA-02` 详情抽屉 | 货主档案列表 → `DetailDrawer` | API-022 | 组件状态（`detailVisible` + 详情对象）；`detail` 参数只在进入时消费一次 |
| `OA-03` 新建货主档案 | `/hub/owner-archives/new` | API-023、API-027 | 组件状态（四项输入、字段错误、幂等键、`acceptNo`） |
| `OA-04` 编辑货主档案 | `/hub/owner-archives/:ownerNo/edit` | API-022（回填）、API-024、API-027 | 组件状态；`version` 每次进入重取 |
| `OA-05` 停用 | 列表页 + `UI-C05` 弹窗 | API-025、API-027 | 组件状态（弹窗开合、原因文本、`acceptNo`） |
| `OA-06` 重新启用 | 列表页 + `UI-C05` 弹窗（与停用同一组件） | API-026、API-027 | 同 `OA-05` |
| `OA-07` 开通货主端账号时选择货主 | 账号页新建抽屉（`UI-C31`） | API-020；提交走契约 1.7 既有接口（待补齐） | 组件状态（「端」取值、已选货主、候选、搜索态） |
`OA-08` 不产生独立请求，依赖账号响应返回的端与归属货主字段，接口归身份模块契约，见「待确认与契约缺口」第 2 条。
| `OA-09` 受理票据展示、轮询与落地 | 列表页、新建页、编辑页、账号页的 `AcceptPanel` | API-027（账号页走身份模块受理查询） | 组件状态；同一页面只留最近一条 |
| `OA-10` 版本冲突与受理失败恢复 | 编辑页（主）、新建页、列表页 | API-022（刷新查看）、API-023~API-026（重提） | 组件状态（输入保留、`version`、幂等键） |

### 表单与校验

| 校验项 | 归属 | 依据与口径 |
|---|---|---|
| 四项必填（`ownerName`、`contactName`、`contactPhone`、`creditCode`） | 前端 | `null`、空串、`trim` 后为空一律按缺失处理；失焦做字段级校验、提交做整体校验，错误在控件下方 4px（`UI-C03`、`OA-03`）；缺失不置灰提交按钮，点提交后就地提示并聚焦首个错误字段 |
| 长度：名称 1~128、联系人 1~64、停用/启用原因 1~200 | 前端 | 与契约 1.5 字段约束同值；`maxlength` 与字数提示同步给，服务端仍会拒 |
| 联系电话 6~32 位，字符集数字与 `+ - ( )` 与空格 | 前端 | 只做字符集与长度，不做号段校验（契约「不做号段校验」） |
| 信用代码 18 位、大写字母（排除 `I`、`O`、`S`、`V`、`Z`）与数字 | 前端 | 只校验长度与字符集，**不校验校验位**、不静默转大小写（契约 1.8 与 Decisions「信用代码校验深度」） |
| 信用代码全局唯一 | 服务端（受理结果） | 前端 MUST NOT 查重、不弹「信用代码已存在」；重复只在受理结果以 `RULE_DUPLICATE_CREDIT_CODE` 出现（`OA-03`、`OA-10`） |
| 名称重复 | **不校验** | 允许同名（`OI-05`）；任何本地去重提示都会误导，帮助文案只写「允许重名，同名以货主编号区分」 |
| 版本冲突（`version` 是否最新） | 服务端 | 前端只带进入表单时取到的 `version`；`CONFLICT_VERSION` 按 `OA-10` 两出口，不做本地比对、不静默重试（`UI-X12`） |
| 状态守卫（停用态不可改档、重复停用、对可用再启用） | 服务端 | 详情页只在已知状态上置灰「编辑」并给原因（`UI-I03`），不据此推断服务端会放行 |
| 功能权限与数据域 | 服务端 | 前端只隐藏入口（`UI-X06`）；建档入口的隐藏见「菜单与权限点」的展示性判断 |
| 归属货主是否存在（账号开通） | 服务端 | 前端只保证「已选」，存在性由服务端 400 `COMMON_9001` 判定（契约 1.7、`OA-07`） |

错误定位与提示层级：

- **字段级**：同步 `PARAM_INVALID` 按契约 1.1 的 `details[].field` 定位到控件；`field` 与契约字段同名（`ownerName`、`contactName`、`contactPhone`、`creditCode`、`reason`），页面维护「`field` → 控件」一张映射表；`field=body`（超限）与未知字段映射不到控件时落表单顶部整页错误块，并显示 `field` 名，不静默丢弃。**前提是请求层把 `details` 透出到错误对象**（当前 `ApiError` 只带 `code`/`message`/`retryable`/`traceId`，见待确认第 6 条）。
- **整页级**：`FORBIDDEN_OWNER` 按 `UI-S03`；`NOT_FOUND_OWNER`（改档目标不存在或已出域）按 `OA-10` 的出口；`AUTHZ_2001`、`COMMON_9001` 走整页错误块 + 就地说明。
- **提示时机与层级**：字段错误走 `UI-C03`；成功提示走 `UI-F04`（2 秒轻提示，说结果不说过程）；失败提示走 `UI-W07` 三段并附错误码（`UI-S06`）；提交中用按钮行内 spinner、控件尺寸不变（`UI-I05`、`UI-C27`）。

防重复提交与幂等键：

- 提交中置 `submitting`，按钮 `loading` 且不再响应点击（`UI-I05`、`UI-A03`）；同一表单的输入控件保持可读但不可提交（`OA-03` 的「表单转只读并进入受理中」）。
- 幂等键取 `newIdempotencyKey()`（`packages/request` 唯一实现），经 `Idempotency-Key` 头随 API-023/024/025/026 下发；键存组件状态。
- **沿用原键**：请求体未变的重试——`DEPENDENCY_UNAVAILABLE`、`RATE_LIMITED`、`IDEMPOTENT_IN_PROGRESS`（`OA-10` 表末列）。
- **换新键**：任何字段被编辑后、`CONFLICT_VERSION` 刷新重提后、`IDEMPOTENT_KEY_CONFLICT` 重提、`INTERNAL_ERROR` 重新提交（`OA-10`「只有传输层的自动重试沿用原键，任何人工重新提交都换新键」）。实现上以「输入变化即清空键」加「刷新重提显式清空」两处保证，不靠调用点各自判断。

### 受理票据的前端状态机与失败分派

写入一律按 D20 设计：提交只拿到 202 受理票据，不得显示成功（`UI-F01`）。状态机与停止条件如下，步骤与文案引用 `OA-09`、`UI-F01`~`UI-F04`。

| 阶段 | 触发 | 界面（引用） | 状态操作 |
|---|---|---|---|
| 提交中 | 点「提交」/ 弹窗主按钮 | 按钮行内 spinner、关闭与遮罩禁用（`UI-I05`、`UI-D12`） | `submitting = true`；失败复原可编辑 |
| 已受理 | 收到 202 与 `acceptNo` | 受理条出现：状态「处理中」、受理号完整可复制（`OA-09`、`UI-W16`）；`replayed=true` 时附「沿用首次受理号」一句 | `acceptNo` 写入页面状态，`AcceptPanel` 接管轮询；表单转只读 |
| 轮询中 | 受理后 1 秒首发，此后每 3 秒 | 状态标签「处理中」（`UI-F02`） | `AcceptPanel` 内部计时，最长 60 秒 |
| 成功 | `acceptStatus=SUCCEEDED` | 整条替换为「成功」+ 结果摘要（货主编号、状态、版本），并按 `UI-F04` 给轻提示 | 停轮询；`UI-X11`：只重取数据，不清筛选、不改游标；新建页给「查看详情」与「返回列表」两出口 |
| 失败 | `acceptStatus=FAILED` + `errorCode` | 整条替换为「失败」+ 三段文案与错误码（`OA-09`、`UI-W07`、`UI-S06`） | 停轮询；表单恢复可编辑并保留输入；出口按本节分派表 |
| 超时 | 60 秒未出结果 | 转「稍后查看」并保留受理号，给「重新查询」（`UI-F02`） | 停止自动轮询；不伪造结果 |

**轮询停止条件**（满足任一即停）：收到 `SUCCEEDED` 或 `FAILED`；满 60 秒转「稍后查看」；API-027 返回不可重试错误（`NOT_FOUND_OWNER` → 「该受理号无法查询」+「返回列表」并停止，`OA-09`）；页面卸载（`AcceptPanel` 的 `onUnmounted`）。**不停止**的情形：429 按 `retryAfterMs` 拉长下一次间隔且保留受理号；503 保留受理号给「重新查询」。

**页面离开时**：停止轮询、丢弃受理条，不写 URL、不写 store；用户可复制受理号，但本期不提供「按受理号查询」页面（见待确认第 4 条）。回到来源列表时按 `UI-X10` 还原筛选与游标，受理结果由那次列表请求体现（`UI-X11`）。

**失败如何映射到界面**：错误码的**文案与人话说明**统一由 `packages/request` 的映射给出（字符串符号码走 `archiveErrorHints`，数字码走 `errorHints`，页面只调 `describeApiError`，不各写一份）；`OA-10` 表的「第三段（下一步）」与「出口」按 `errorCode` 在一张页面无关的分派表里维护，新建页、编辑页与列表页共用同一处，不按页复制。本期该表需新增两条符号码文案：`RULE_DUPLICATE_CREDIT_CODE`、`NOT_FOUND_OWNER`（`FORBIDDEN_OWNER`、`STATE_INVALID`、`CONFLICT_VERSION`、`PARAM_INVALID`、`IDEMPOTENT_IN_PROGRESS`、`IDEMPOTENT_KEY_CONFLICT`、`RATE_LIMITED`、`DEPENDENCY_UNAVAILABLE`、`INTERNAL_ERROR` 已在表内）。

| 错误码 | 出现位置 | 界面落点 | 映射维护处 | 幂等键 |
|---|---|---|---|---|
| `PARAM_INVALID` | 同步 | 按 `details[].field` 就地标注并聚焦（`OA-10`） | `archiveErrorHints` + 页面字段映射 | 新键 |
| `RULE_DUPLICATE_CREDIT_CODE` | 受理结果 | 受理条失败 + 出口「修改输入」「按信用代码筛选列表」 | 新增到 `archiveErrorHints`；出口取 `OA-10` 表 | 新键 |
| `STATE_INVALID` | 受理结果 | 受理条失败 + 「刷新查看」「返回列表」 | 既有映射 + `OA-10` 表 | 新键 |
| `CONFLICT_VERSION` | 受理结果 | 编辑页冲突面板（`UI-X12`）+ 两出口；停用/启用同在受理条给出两出口 | 既有映射 + `OA-10` 表 | 新键 |
| `NOT_FOUND_OWNER` | 受理结果、API-022 同步 | 抽屉内错误块 / 受理条失败 + 「返回列表」 | 新增到 `archiveErrorHints` | — |
| `FORBIDDEN_OWNER` | 同步 | 整页 `UI-S03` + 「返回上一页」 | 既有映射 | — |
| `IDEMPOTENT_IN_PROGRESS` | 同步 | 按钮按 `retryAfterMs` 倒计时后自动用原键重提 | 既有映射 | 原键 |
| `IDEMPOTENT_KEY_CONFLICT` | 同步 | 就地提示 + 「重新提交」 | 既有映射 | 新键 |
| `RATE_LIMITED` | 同步 | 按钮倒计时并禁用 | 既有映射 | 原键 |
| `DEPENDENCY_UNAVAILABLE` | 同步或受理结果 | 受理条失败 + 「重试」（按 `retryAfterMs` 退避），不改查空结果 | 既有映射 | 原键 |
| `INTERNAL_ERROR` | 同步或受理结果 | 受理条失败 + 「重新提交」「复制 traceId」 | 既有映射（`describeApiError` 自动附 `traceId`） | 新键 |

`AcceptPanel` 需按本次口径做三处组件层调整（改组件、不在页面复制）：首次查询改为受理后 1 秒（现为即时首查）、`replayed` 提示、同一页面只保留最近一条受理条。前一条与 `UI-F02`「每 3 秒轮询」不完全一致，登记为待确认第 3 条。

### 异常与空态

| 类型 | 出现页面 | 触发请求 | 视觉与出口（引用） |
|---|---|---|---|
| 加载中 | 列表首屏、编辑页回填、抽屉正文 | API-021、API-022 | 首屏与回填超 300ms 用骨架屏（`UI-S01`、`UI-C24`、`UI-D11`）；查询与翻页用表格内 loading，不重绘骨架（`OA-01`） |
| 空数据 | 列表（无档案 / 筛选过窄两套文案）、货主选择器（无可用货主） | API-021、API-020 | 列表按 `UI-S02` 两套主次文案与出口「新建」「重置」；选择器主文案「暂无可用货主」+ 出口跳 `/hub/owner-archives`（不直连 `/new`，因为代建与账号页的角色不一定持写权限） |
| 无权限 | 列表整页、抽屉内、账号页选择器 | API-021 返回 403 `FORBIDDEN_OWNER`/`AUTHZ_2001`；API-022 返回 `NOT_FOUND_OWNER`；API-020 返回 `AUTHZ_2001` | 整页按 `UI-S03` 并写明需联系的角色；抽屉内按 `UI-S04` 错误块 + 「关闭」（`OA-02`）；选择器给「无权检索货主（AUTHZ_2001）」且提交保持置灰（`OA-07`） |
| 接口失败 | 列表、抽屉、表单、选择器、受理条 | API-020~API-027 | 区域内错误块 + 错误码 + 「重试」，保留筛选与输入（`UI-S04`、`UI-S06`）；表单顶部错误块仅给整页级错误 |
| 降级数据 | **本变更不出现** | — | API-020~API-027 读权威库、成功响应不带 `dataTimestamp`，页面不装配 `DegradedBanner`、不显示伪造的数据时间戳（`UI-S05`、`UI-S07` 不适用，契约 1.1「查询元信息」）；若后续引入副本，按 D19 在页头显示数据时间戳与来源标识，本期不预留入口。唯一要守的边界是：503 必须显示为失败，不得显示成 200 空列表（契约 1.2「降级」） |

### 待确认与契约缺口

| 序 | 事项 | 影响 | 需谁定 | 是否阻塞 |
|---|---|---|---|---|
| 1 | 账号开通请求体缺「端」与 `ownerId`（契约 1.7 只写「`client=OWNER` 时必填 `ownerId`」，既有 `CreateRequest` 无此两字段；设计章回写登记已列为阻塞） | `OA-07` 的「端」单选与归属货主没有提交落点，开不出货主端账号 | 身份模块契约负责人 | **是**（阻塞账号页提交路径；不阻塞货主档案三页） |
| 2 | 账号列表与详情响应缺归属货主字段（`ownerId` 与 `ownerNo`/`ownerName`），也缺「端」标识 | `OA-07` 的「所属货主」列、`OA-08` 的只读展示与编辑回显都没有数据来源；契约没有「按 `ownerId` 查货主」的接口，前端无法反查，也不得用 `ownerId` 数字直接显示给用户 | 身份模块契约负责人 | **是**（阻塞列与只读展示；两处显示「—」不推断） |
| 3 | `AcceptPanel` 首发时点为受理后 1 秒，`UI-F02` 只写「每 3 秒轮询」 | 组件按本次口径改，规范未登记首发时点，后续同类页面会各自决定 | `12` 维护方 | 否 |
| 4 | 受理条离开页面后是否可找回（`ui.md` 待确认第 6 条） | 本期结论：不跨页保留、不提供「按受理号查询」页面——没有对应 `UI-*` 交互，不为它自造入口 | 前端设计 + 产品（若要找回，需先在 `12` 立规范） | 否 |
| 5 | API-020 的 **BREAKING** 改造会打断既有代建页 | 既有 `ArchivesView.vue` 用 `ownerCode`/`name` 查询、条目无 `ownerNo`，不改造即 `PARAM_INVALID`；须与本变更同批改 `packages/request` 的 `OwnerOptionQuery`/`OwnerOption` 与抽出的共享选择器 | 前端（本变更内完成） | 否（须与契约同批上线） |
| 6 | 请求层未透出契约 1.1 的 `details` 数组 | 字段级错误无法按 `details[].field` 就地定位，`OA-03`/`OA-10` 的「就标注该字段」做不到；`ApiError` 需补 `details` | 前端（`packages/request`） | 否（契约已有字段，缺的是前端承载） |
| 7 | `DataTable` 键集模式没有「每页 20/50/100」选择 | `UI-C08` 要求每页条数可选、`OA-01` 要求改每页条数重置游标；组件目前只渲染「已加载 N 条 + 上一页/下一页」 | 前端组件 + `12` 维护方（键集分页的分页控件退化规则，与 `ui.md` 待确认第 1 条同源） | 否 |
| 8 | `05` 未登记 `owner.hub.read`/`owner.hub.write` 编码，`21` 的 E-20 只是建议行 | 本章的菜单与按钮映射缺文档依据；前端取值按契约 E-20 写死在一处 | 权限设计方 / 设计文档维护方 | 否 |
| 9 | `UI-X06` 第二行说「有功能权限、无数据域 → 页面可见、数据为空、按 `UI-S03`」，`OA-03` 要求非全域账号的「新建」入口不渲染 | 同一情形在两处表述不同；本章按 `OA-03` 实现入口不渲染，并在列表页给一句说明 | `12` 维护方 | 否 |

### 边界说明

不做：移动作业端原生 App（Kotlin + Android，另立工程，`pda-design` 负责）、后端实现与 SQL、接口路径与字段定义、交互动线与视觉规格、货主端与单仓端页面、货主档案的导入导出与批量操作与打印（契约不提供）、货主自助注册、按仓库筛选或仓级授权（`D40`）、前端侧的任何业务判定（信用代码判重、能否停用、版本是否最新一律以服务端返回为准，`UI-I11`）。

前端在本变更的交付边界是：三个货主档案路由与一个账号页改造、`permissions.ts` 的两枚权限点键名与路由 meta、`packages/request` 的 API-021~API-027 声明、错误码映射补两条、`@wms/design` 的三处组件改造（`UI-C05` 弹窗外壳、货主选择器共享化、`AcceptPanel` 三点调整）与 `DataTable` 键集分页的每页条数。

## 后端设计

本章只写后端实现侧：模块落点、聚合与状态机、强制手段、表与脚本、事务与并发、受理与下行、鉴权与错误码。接口路径、字段、错误码取值以 `## API 接口契约` 为唯一来源，本章不新增也不修改任何一条；本章需要而契约没有的内容，集中登记在「回写登记」小节。

### 模块与依赖

货主档案是 **M01 主数据的独立聚合**，不是商品档案的一个分支：它有自己的表、状态机与编号规则，`hub_sku_archive.owner_id` 只是外部引用值，不持有货主档案实体，停用货主也不级联改 SKU 状态。

| 落点 | 模块与包 | 内容 | 备注 |
|---|---|---|---|
| 货主档案规则 | `wms-kernel` · `com.huoling.wms.masterdata.domain` | `OwnerStatus`、`OwnerAction`、`OwnerStateMachine`、`OwnerFields`（四项必填的格式规则）、`OwnerCreditCodeRule`、`OwnerNumberRule`、`OwnerVersionRule`；纯 Java，不依赖 Spring | 不变量只在这里定义（`01`「不变量只允许在 kernel 定义」） |
| 常量 | `wms-common` · `com.huoling.wms.common.owner`、`common.error` | `OwnerPermissionCodes`（两枚权限点）、`OwnerContractCodes`（契约错误码常量，含两枚新增码） | 只有常量不含校验逻辑 |
| 货主档案读写 | `wms-hub` · `com.huoling.wms.masterdata.owner`（api / application / domain / infrastructure） | API-021 至 API-027 的控制器、应用服务、仓储与 Mapper | 新增包，与 `archive` 平级 |
| 选择器改造 | `wms-hub` · `masterdata.archive.application.OwnerSelectorService`、`infrastructure.persistence.OwnerDirectoryMapper/PO` | 数据源从「货主端账号派生」换成 `wms_owner`；`ownerCode` / `name` 两个旧参数删除，改为 `ownerNo` / `ownerName` | API-020 同号改造，投影类保留但换查询与字段 |
| 受理与幂等机制 | `wms-hub` · `masterdata.acceptance`（application / domain） | 由 `masterdata.archive` 上移 `ArchiveAcceptanceService`、`ArchiveIdempotencyService`、补偿扫描与仓储，两个业务包共用一份 | 见「幂等与异步受理」 |
| 身份模块收窄 | `wms-hub` · `masterdata.identity` | 账号开通/修改校验货主绑定、登录与令牌校验拒绝停用货主、状态缓存失效 | 见「账号与登录」 |
| 货主状态副本 | `wms-site` · `com.huoling.wms.site`（domain / infrastructure / application） | 消费 `owner.status.changed`、维护 `site_owner_status`、提供受理前判定闸门 | 只读副本，权威源仍在 Hub（D12） |
| 货主端与开放接口 | `wms-open`、`/api/owner/**` | 本变更不改 | — |

依赖方向：

- Hub、Site 单向依赖 kernel 与 common；货主档案规则只写在 kernel，Hub 与 Site 只调用，MUST NOT 各写一套校验。
- `masterdata.owner` 与 `masterdata.archive` MUST NOT 有编译期依赖（双向），二者共用能力下沉到 `masterdata.acceptance`。ArchUnit 新增两条规则（编号沿用 `14` 的 AR 序列，本变更占 AR-11）：

  ```
  noClasses().that().resideInAPackage("..masterdata.owner..")
      .should().dependOnClassesThat().resideInAPackage("..masterdata.archive..")
  noClasses().that().resideInAPackage("..masterdata.archive..")
      .should().dependOnClassesThat().resideInAPackage("..masterdata.owner..")
  ```

- Site 侧只允许引用 kernel 的 `OwnerStatus` 与 `OwnerFields`，MUST NOT 引用 Hub 的 `OwnerArchive` 聚合（`UnitIsolationRulesTest` 增加一条「单仓单元不引用 Hub 领域实体」）。
- 既有 AR-01（分层单向）、AR-02（domain 不依赖框架）、AR-09（跨模块只传 DTO）不改；`masterdata` 不在 AR-09 的十个领域模块清单内，本条不构成违规。

### 领域模型与状态机

**聚合与实体**

| 对象 | 类型 | 权威源 | 职责与边界 |
|---|---|---|---|
| `OwnerArchive` | 聚合根（`@AggregateRoot`） | Hub | 唯一身份是 `ownerId` 与 `ownerNo`；行为只有「建档、整档替换（改档）、停用、启用」四个，状态与版本只在聚合内推进 |
| `OwnerStatusChange` | 聚合内值对象 | Hub | 最近一次状态变更（`action` / `reason` / `operatorId` / `changedAt`），落在聚合同一行，**不另建聚合、不另建留痕表**——避免跨聚合同事务 |
| `OwnerArchiveQuery` | 查询参数值对象 | Hub | 四类筛选、排序白名单、键集游标；不进聚合 |
| `Acceptance` / 幂等记录 | 技术设施（非聚合） | Hub | 复用 `masterdata.acceptance` 的受理记录与幂等记录，见「幂等与异步受理」 |
| `OwnerStatusSnapshot` | 只读副本（非聚合） | Site | `site_owner_status` 行，不承载业务行为，只回答「这个货主现在能不能接新单」 |

**状态迁移表**（`ACTIVE` / `DISABLED` 两态，与契约 1.1、1.5 一致）

| 当前状态 | 动作（`OwnerAction`） | 结果状态 | 允许条件 | 违反时错误码 |
|---|---|---|---|---|
| —（无档案） | `CREATE` | `ACTIVE` | 四项必填齐全、信用代码全局唯一、编号由系统生成 | `PARAM_INVALID` / `RULE_DUPLICATE_CREDIT_CODE` |
| `ACTIVE` | `EDIT` | `ACTIVE` | `version` 匹配；不改编号、不改状态 | `STATE_INVALID`（停用态入此列）/ `CONFLICT_VERSION` |
| `ACTIVE` | `DISABLE` | `DISABLED` | `version` 匹配；`reason` 必填 | 对已停用再停用 → `STATE_INVALID` |
| `DISABLED` | `ENABLE` | `ACTIVE` | `version` 匹配；`reason` 必填 | 对可用再启用 → `STATE_INVALID` |
| `DISABLED` | `EDIT` | 拒绝 | — | `STATE_INVALID`（须先启用） |
| 任意 | 物理删除 | 不存在 | 无此动作 | 库层 `before delete` 触发器一律拒绝（INV-18，属主也绕不过） |

**禁止的迁移**：`DISABLED` 直接改档；重复停用；重复启用；任何「系统自动恢复可用」路径（定时任务、重启、迁移重跑、状态机初始化都 MUST NOT 写 `ACTIVE`）；任何物理删除。停用不是终态（可再启用），但没有系统路径能产生 `DISABLED → ACTIVE`。

**不变量清单**

| 编号 | 不变量 | 依据 |
|---|---|---|
| OI-01 | 货主编号系统生成、全局唯一、不可变（停用与启用都不变） | spec「货主编号由系统生成且不可变」 |
| OI-02 | 状态只有可用与停用两值，且不表达合同是否生效 | spec「只有可用与停用两态」、D33 |
| OI-03 | 名称、联系人、联系电话、统一社会信用代码四项必填 | spec「建档四项必填」、`proposal.md` 已定口径 |
| OI-04 | 统一社会信用代码全局唯一，停用档同样占位 | spec「唯一性只认编号与信用代码」 |
| OI-05 | 名称允许重复，不作为任何唯一性条件 | spec「同名不阻断」 |
| OI-06 | 数据不物理删除，停用以状态表达 | INV-18 |
| OI-07 | 读写同时校验功能权限与货主数据域 | INV-19 |
| OI-08 | 货主域为空直接拒绝，不回落成「全部」 | INV-06、`05` 第 3 节 |
| OI-09 | 版本号每次成功写入递增一，改档/停用/启用必须带 `version` | spec「改档带版本号」 |
| OI-10 | 货主端账号的归属货主写定后不可变 | spec「绑定不可改」 |
| OI-11 | 停用阻断以该货主为对象的新建业务，不阻断存量单据与任务 | spec「停用阻断新建但不阻断存量」 |
| OI-12 | 停用不自动恢复，启用只能由人发起并留痕 | spec「重新启用必须由人操作」 |
| OI-13 | 选择器只暴露可用货主，且不再从账号派生 | spec「货主选择器只暴露可用货主」 |

**需登记到 `22` 的状态机行（建议 S-09、T-07）**

| 编号 | 对象 | 完整取值 | 推进源 | 禁止的转换 | 权威文档 | 状态 |
|---|---|---|---|---|---|---|
| S-09 | 货主档案 | 可用、停用（两态） | 运营端建档、停用、启用动作 | 停用态直接改档；系统自动恢复可用；物理删除 | `11`、本次变更契约章 | 已定（2026-11-25） |

| 编号 | 例外场景 | 事务范围 | 理由 | 来源 |
|---|---|---|---|---|
| T-07 | 货主档案状态变更（建档、停用、启用） | 状态与版本、状态留痕列、本地消息表（`hub_outbox`）同一本地事务写入 | 拆开会出现「货主已停用但单仓端永不感知」，与 T-06 同型 | 本次变更设计章 |

**需登记到 `21` 的枚举行（建议 E-18 至 E-20）**

| 编号 | 枚举 | 分类维度 | 完整取值 | 权威文档 | 互斥证明 | 穷尽证明 | 状态 |
|---|---|---|---|---|---|---|---|
| E-18 | 货主档案状态 | 生命周期 | 可用、停用（2）；取值 `ACTIVE`、`DISABLED` | `22`、本次变更契约章 | 两态互斥；是否停用只由这一列表达，不另设停用标记 | 从建档到停用再启用只落在这两个取值；终态不存在，可逆但有守卫 | 已定（2026-11-25） |
| E-19 | 货主档案状态变更动作 | 动作 | 建档、停用、启用（3）；取值 `CREATE`、`DISABLE`、`ENABLE` | 本次变更契约章 | 一个动作只对应一条合法迁移 | 状态变更只有这三种触发来源；系统自动路径不存在 | 已定（2026-11-25） |
| E-20 | 货主档案权限点 | 端与动作 | 运营读、运营写（2）；取值 `owner.hub.read`、`owner.hub.write` | `05`、本次变更契约章 | 按动作切分，同一动作不跨端复用；货主端无档案权限点 | 覆盖运营端在货主档案上的全部入口（查询与读权限同点、四个写接口同点） | 已定（2026-11-25） |

### 强制手段分层

每条不变量至少有一层兜底，没有手段的规则不写进契约。

| 规则 | 数据库约束 | 应用校验 | 定时核对 / 构建期 | 依据 |
|---|---|---|---|---|
| OI-01 编号唯一不可变 | `uk_wms_owner_no` 唯一索引；触发器拦 `owner_no` 与 `id` 变更 | 建档入口不接受 `ownerNo`（未知字段即 `PARAM_INVALID`），无更新编号的写路径 | 无（唯一索引即终局） | spec「编号不可变」 |
| OI-02 两态闭集 | `ck_wms_owner_status`；触发器拦「非 DISABLE/ENABLE 造成的状态变化」 | kernel `OwnerStateMachine` 守卫 | 枚举登记见 `21` E-18 | spec「两态」 |
| OI-03 四项必填 | 四列 `not null` + `ck_wms_owner_name` / `_contact_name` / `_contact_phone` / `_credit_code`（长度与字符集） | kernel `OwnerFields` 逐字段校验，`details[].field` 定位到具体字段 | 无 | spec「四项必填」 |
| OI-04 信用代码唯一 | `uk_wms_owner_credit_code` 唯一索引（停用档同样占位） | 执行前预判只作提示，最终以索引冲突为准 | 索引冲突即拒绝，无需对账 | spec「信用代码唯一」 |
| OI-05 名称可重复 | **不建**唯一索引（反证式约束） | 无去重逻辑 | 构建期：`wms_owner` 上不出现含 `owner_name` 的唯一索引 | spec「同名不阻断」 |
| OI-06 不物理删除 | `before delete` 触发器一律 `raise exception`（表属主也绕不过）；`revoke delete, truncate from public` 只对非属主角色生效，属主权限不回收，不能单靠它 | 仓储接口不提供 `delete` | 构建期：`owner` 包内不出现 `delete from wms_owner` | INV-18 |
| OI-07 双重校验 | — | 统一鉴权切面（`PermissionInterceptor` + `@RequiresPermission`）+ 数据域在仓储层注入 | 越权用例 | INV-19 |
| OI-08 空域拒绝 | — | `OwnerAccessGuard`：域为空抛 `FORBIDDEN_OWNER`，MUST NOT 回落全量；建档要求全域 | 越权用例 | INV-06、`05` |
| OI-09 版本递增 | 触发器要求 `new.version = old.version + 1`；`update ... where version = ?` 抢行 | kernel `OwnerVersionRule` 前置比对 | 并发用例 | spec「改档带版本号」 |
| OI-10 绑定不可变 | `fk_wms_account_owner` + 触发器拦 `wms_account.owner_id` 变更 | `AccountCommandService` 拒绝携带不同 `ownerId` 的修改 | 定时核对：持货主端角色但 `owner_id` 为空的账号 | spec「绑定不可改」 |
| OI-11 停用阻断新建 | — | Site 受理闸门读 `site_owner_status`；缓存无记录即拒绝 | 对账脚本（运维侧，非接口）比对 Hub 与各仓副本 | spec「停用阻断新建」 |
| OI-12 不自动恢复 | 触发器只放行 `ENABLE` 造成的 `DISABLED → ACTIVE` | 无任何定时任务写 `ACTIVE`；启用接口是唯一入口 | 构建期：仓库内不存在写 `status='ACTIVE'` 的定时任务 | spec「重新启用必须由人操作」 |
| OI-13 选择器只读可用 | 查询条件固定 `status = 'ACTIVE'` | 投影查询无写路径 | 验收用例「断掉账号数据仍可查到货主」 | spec「选择器」 |

沿用基线、本次不改动：库存与库位的 `location_id NOT NULL` + 外键与「无虚拟库位」（INV-01、D26、D27）；库存唯一键六字段 `owner_id`、`location_id`、`sku_id`、`batch_no`、`stock_status`、`serial_no` 的语义、索引与 `serial_no NOT NULL DEFAULT ''` 策略均不变（D29）；`qty_allocated <= qty_on_hand` 的库层 `CHECK`（INV-08）。货主档案不持有库位、批次、序列号字段，也不参与库存唯一键以外的任何约束；本次只给 `wms_account.owner_id` 与 `hub_sku_archive.owner_id` 建引用，库存表不加外键、不改唯一键，停用货主不影响存量库存的可查与可动。

### 数据模型与 DDL

**表清单**

| 表名 | 用途 | 权威源 | 新增或变更 | 脚本编号 |
|---|---|---|---|---|
| `wms_owner` | 货主档案主表（编号、名称、联系人、电话、信用代码、状态、版本、审计列） | Hub（M01 主数据） | 新增 | `V20261136__hub_owner_archive.sql` |
| `wms_owner_import` | 存量 `owner_id` 的补录清单（迁移用，不进业务链路） | Hub | 新增 | 同上 |
| `wms_account` | 补货主外键与索引（不改列） | Hub | 变更 | `code/db/ops/V20261138__hub_owner_import_and_foreign_keys.sql`（第二窗口，不进自动迁移） |
| `hub_sku_archive` | 补货主外键（不改列） | Hub | 变更 | 同上 |
| `hub_archive_acceptance` | 加 `subject_owner_id`（受理单的目标货主，可空）；`idempotency_key` 放宽到 `varchar(128)` | Hub | 变更 | `V20261140__hub_acceptance_subject_owner.sql` |
| `hub_idempotency` | `idempotency_key` 放宽到 `varchar(128)`（对齐契约 1.3 的 1 至 128 位，不改结构） | Hub | 变更 | 同上 |
| `site_owner_status` | 单仓端货主状态只读副本 | Site | 新增 | `V20261141__site_owner_status.sql` |

`hub_outbox`、`hub_idempotency` 结构与既有商品档案一致，本次不改表，只新增事件类型与作用域取值。`wms_owner` 落在 Hub 主数据模块，与 `11` 第 218 行「主数据（M01）`wms_owner`」一致；`site_owner_status` 是副本，不进 `11` 的主数据清单。

**`wms_owner` 列级定义**（`V20261136__hub_owner_archive.sql`）

```sql
create extension if not exists pg_trgm;   -- ownerName 模糊筛选的索引依赖（PostgreSQL 标准 contrib）

create sequence if not exists seq_wms_owner_no start with 1 increment by 1;

create table if not exists wms_owner (
  id                bigint       primary key,                 -- 雪花，应用生成；存量沿用原 owner_id
  owner_no          varchar(32)  not null,                    -- OW + 序号，不可变
  owner_name        varchar(128) not null,
  contact_name      varchar(64)  not null,
  contact_phone     varchar(32)  not null,
  credit_code       varchar(18)  not null,                    -- 统一社会信用代码，18 位大写
  status            varchar(16)  not null default 'ACTIVE',
  status_action     varchar(16)  not null default 'CREATE',   -- CREATE / DISABLE / ENABLE
  status_reason     varchar(200),                             -- CREATE 时为空
  status_changed_at timestamptz  not null,
  status_changed_by bigint       not null,
  version           bigint       not null default 0,
  created_at        timestamptz  not null,
  created_by        bigint       not null,
  updated_at        timestamptz  not null,
  updated_by        bigint       not null,
  constraint ck_wms_owner_status        check (status in ('ACTIVE', 'DISABLED')),
  constraint ck_wms_owner_status_action check (status_action in ('CREATE', 'DISABLE', 'ENABLE')),
  constraint ck_wms_owner_reason        check ((status_action = 'CREATE') = (status_reason is null)),
  constraint ck_wms_owner_no_format     check (owner_no ~ '^OW[A-Z0-9]{6,30}$'),
  constraint ck_wms_owner_name          check (btrim(owner_name) <> '' and char_length(owner_name) <= 128),
  constraint ck_wms_owner_contact_name  check (btrim(contact_name) <> '' and char_length(contact_name) <= 64),
  constraint ck_wms_owner_contact_phone check (contact_phone ~ '^[0-9+() -]{6,32}$'),
  constraint ck_wms_owner_credit_code   check (credit_code ~ '^[0-9A-HJ-NP-RT-UW-Y]{18}$'),
  constraint ck_wms_owner_version       check (version >= 0)
);
```

字段口径与依据：

| 列 | 口径 | 依据 |
|---|---|---|
| `id` | `bigint` 主键，雪花由应用生成（`11` 主键口径）；存量档案沿用原 `owner_id` 值，使 `wms_account.owner_id` 与 `hub_sku_archive.owner_id` 无需改写 | `11`、D12 |
| `owner_no` | 由 `seq_wms_owner_no` 生成 `'OW' || to_char(nextval(...), 'FM000000')`；`FM` 只补零不截断，序号超过六位自然变长（契约只承诺 8 至 32 位与 `^OW[A-Z0-9]{6,30}$`） | 契约 1.8 API-020、1.11「编号规则」 |
| `credit_code` | `varchar(18)` + 正则：大写字母排除 `I`、`O`、`S`、`V`、`Z` 加数字；**不校验校验位** | 契约 1.8「信用代码校验深度」 |
| `status` / `status_action` / `status_reason` | 三列共同表达契约 1.5 的 `lastStatusChange`（`action` / `reason` / `operator` / `changedAt`）；`status_reason` 与 `status_action` 由 `ck_wms_owner_reason` 绑死：`CREATE` 必空、`DISABLE` / `ENABLE` 必填 | 契约 1.5、spec「停用必须填写原因」 |
| `version` | 建档为 0，此后每次成功写入 +1（改档、停用、启用都算） | 契约 1.5、OI-09 |
| 审计四列 | `created_by` / `updated_by` 存账号主键；时间为 `timestamptz` 存 UTC（D38 的日切口径不适用：货主档案无计费日切） | 契约 1.5、D24 |

**索引**（每条对应一条具体查询，无查询支撑的不建）

| 索引名 | 定义 | 对应查询 |
|---|---|---|
| `uk_wms_owner_no` | unique (`owner_no`) | API-022 按编号寻址；API-021 `ownerNo` 精确筛选；`sortBy=ownerNo` 的键集分页 |
| `uk_wms_owner_credit_code` | unique (`credit_code`) | 建档/改档的信用代码唯一性（含并发后到者）；API-021 `creditCode` 精确筛选 |
| `idx_wms_owner_name_trgm` | gin (`owner_name` gin_trgm_ops) | API-020 / API-021 的 `ownerName` 模糊筛选（`%keyword%` 无法走 btree） |
| `idx_wms_owner_sort_name` | (`owner_name`, `owner_no`) | `sortBy=ownerName` 的键集分页（游标锚在排序键与 `ownerNo` 组合上，契约 API-021「分页/排序」） |
| `idx_wms_owner_sort_updated` | (`updated_at` desc, `owner_no`) | `sortBy=updateTime` 与 `updatedFrom` / `updatedTo` 闭区间筛选 |

`status` 是低基数列，不单独建索引：筛选后行数已经很小，走索引反而多一次回表；停用档占比低，选择器固定带 `status = 'ACTIVE'`，由 `idx_wms_owner_sort_*` 覆盖翻页。

**不可变与版本守卫**（`V20261136` 同一脚本）

```sql
create or replace function wms_owner_guard() returns trigger as $$
begin
  if new.id <> old.id or new.owner_no <> old.owner_no then
    raise exception '货主主键与货主编号创建后不可变更';
  end if;
  if new.status is distinct from old.status then
    if not ((old.status = 'ACTIVE'   and new.status = 'DISABLED' and new.status_action = 'DISABLE')
         or (old.status = 'DISABLED' and new.status = 'ACTIVE'   and new.status_action = 'ENABLE')) then
      raise exception '货主状态只允许 可用→停用(DISABLE) 与 停用→可用(ENABLE)';
    end if;
  end if;
  if new.version <> old.version + 1 then
    raise exception '货主档案版本号必须逐次递增一';
  end if;
  return new;
end;
$$ language plpgsql;

drop trigger if exists trg_wms_owner_guard on wms_owner;
create trigger trg_wms_owner_guard before update on wms_owner
  for each row execute function wms_owner_guard();

-- INV-18：属主天然可删，revoke 只对非属主生效，必须另有拒绝删除的触发器
create trigger trg_wms_owner_no_delete before delete on wms_owner
  for each row execute function wms_owner_reject_delete();
revoke delete, truncate on wms_owner from public;
```

触发器只挡「绕过应用的写」，正常路径仍由聚合与 kernel 守卫先在应用层拒绝，两层同一口径。

**软删除与唯一键的关系**：货主档案不设删除标记，停用即状态；因此 `uk_wms_owner_credit_code` 是**不带谓词的完整唯一索引**，停用档继续占位。这与 `hub_sku_archive` 用部分唯一索引不同，原因在业务口径：SKU 停用后同编码可被新草稿复用（`26`），货主停用后同一法人不允许再建第二条档案（`proposal.md` 已定「信用代码全局唯一」）。

**存量 `owner_id` 回填与加约束顺序**（三脚本、两个发布批次；第二窗口不进自动迁移）

前提事实：`wms_account.owner_id` 与 `hub_sku_archive.owner_id` 现在都是裸数字，没有外键；信用代码、联系人、联系电话在存量数据里没有来源，只能人工补录（`18` 第 47 行「货主档案 导入 人工逐条核对」）。

| 步 | 脚本 | 做什么 | 失败处置 |
|---|---|---|---|
| 一 | `V20261136__hub_owner_archive.sql` | 建 `wms_owner`（四项**从一开始就 `not null`**）、序列、索引、触发器；同时建 `wms_owner_import` 清单表 | 建表失败整体回滚，不影响既有表 |
| 二 | `V20261137__hub_owner_import_candidate.sql` | 从 `wms_account.owner_id` 与 `hub_sku_archive.owner_id` 的**并集**生成候选行（`owner_name` 初值取该货主下最早的账号名，其余三项留空），`raise notice` 打出待补录条数 | 可重复执行（`on conflict (owner_id) do nothing`）；重复跑不覆盖已补录内容 |
| 三 | `code/db/ops/V20261138__hub_owner_import_and_foreign_keys.sql`（运维脚本，**不在 Flyway 自动迁移目录**） | 校验四项齐全、清单内信用代码不重复、引用无缺失 → 写入 `wms_owner`（`id` 取原 `owner_id`，`owner_no` 取序列，`created_by = 0` 表示迁移账号）并回写 `imported_at` → 再校验无孤儿 → 给两列加外键并补索引 | 任一校验不过整体回滚（运维窗口内单事务执行），按输出补录后重跑；已导入行按 `imported_at` 跳过，脚本可重复执行 |

**发布批次**：第一、二步随应用发布由 Flyway 自动执行，补录未完成不影响任何既有功能；第三步是运维窗口脚本，由发布负责人用 `psql -f` 手工执行，条件是补录清单清零、运营确认的「不保留」历史停用货主已按 `18` 处置完毕。

**为什么第三步不进 Flyway 目录**：补录依赖运营逐条填信用代码、联系人与电话，时间不由研发控制。放进自动迁移，只要清单里有一条不齐备，任何环境（新装、本地开发、测试）启动即中止，把运营节奏变成发布阻塞；放到运维目录后，中间态只是缺少引用完整性，读写不受影响，外键在窗口内一次补齐。

**中间态说明**：第一窗口期间 `wms_account.owner_id` 与 `hub_sku_archive.owner_id` 无外键约束，写入非法货主标识在库层拦不住，只靠应用层校验；这一点在发布说明里注明，窗口结束后由外键接管。

**不保留清单**：四项必填意味着联系不上、已停用多年的历史货主无法补齐。由运营在上线前出一份不保留清单，这些货主不写入 `wms_owner`，对应账号与 SKU 引用按 `18` 的清单处置；不得为凑外键编造信用代码，否则唯一索引会把编造值固化成权威数据，后续再改就要走变更流程。

关键写法与锁影响：

```sql
-- 第三步后半：先 not valid 再 validate，避免长时间 SHARE ROW EXCLUSIVE 全表锁
alter table wms_account
  add constraint fk_wms_account_owner foreign key (owner_id)
  references wms_owner (id) on update restrict on delete restrict not valid;
alter table wms_account validate constraint fk_wms_account_owner;   -- 只取 SHARE UPDATE EXCLUSIVE，不阻塞读写

alter table hub_sku_archive
  add constraint fk_hub_sku_archive_owner foreign key (owner_id)
  references wms_owner (id) on update restrict on delete restrict not valid;
alter table hub_sku_archive validate constraint fk_hub_sku_archive_owner;

create index if not exists idx_wms_account_owner on wms_account (owner_id);  -- 外键列必须有索引
```

`hub_sku_archive.owner_id` 已有 `idx_hub_sku_archive_owner_status`（首列 `owner_id`），满足外键列索引要求，不重复建。`wms_owner_import` 保留在库内（含补录留痕），不删表。

**变更型脚本说明**

| 脚本 | 语句 | 回填 | 回滚 |
|---|---|---|---|
| `V20261140` | `alter table hub_archive_acceptance add column if not exists subject_owner_id bigint` + 注释 + `idx_hub_archive_acceptance_subject (scope_key, subject_owner_id, created_at desc)`；另把 `hub_archive_acceptance.idempotency_key` 与 `hub_idempotency.idempotency_key` 放宽到 `varchar(128)` | 不回填：商品档案受理单该列恒为空，语义不一致的默认值比空值更危险；列宽放宽是元数据变更，既有记录原样可用 | `drop column`（新列，无依赖）；列宽不回退（回退会截断已写入的长键） |
| `V20261141` | 建 `site_owner_status`（`owner_id` 主键、`owner_no` 唯一、`status` 闭集、`source_version`、`event_id`、`data_timestamp`、`updated_at`） | 不回填：上线后由下行事件填充；未下行到的货主按「缓存无记录」处理（见「降级」） | `drop table`（副本可重建） |

本变更不向既有表新增 `not null` 列，因此不触发「加可空列 → 分批回填 → 加约束」三步；上表的两个变更脚本都是可空列、新表或纯列宽放宽，已写明理由。`V20261140` 的列宽放宽在 PostgreSQL 上是元数据操作、不重写表，但会短暂取 `ACCESS EXCLUSIVE` 锁，须放在业务低峰执行。所有脚本只新增、不改历史脚本，且均可重复执行（`if not exists` / `create or replace` / `on conflict do nothing`）。

**权限点初始化脚本**（`R__hub_owner_permissions.sql`，repeatable），见「鉴权与数据域落点」。

### 事务边界与并发控制

一个用例一个本地事务；跨聚合一致性只走本地消息表（`22` T-05 / T-07），不引入分布式事务。

| 用例 | 事务范围 | 事务外的补偿 | 依据 |
|---|---|---|---|
| 受理提交（API-023 至 API-026 的入口） | 幂等占位记录 + 受理记录（`PROCESSING`）同事务写入，提交后立即返回 `acceptNo` | 线程池拒绝时把受理单落 `FAILED` 并返回可重试错误；`ArchiveAcceptanceCompensator` 每 60 秒扫 `PROCESSING` 超时 | 契约 1.3、既有受理机制 |
| 建档执行 | `wms_owner` 插一行（状态、留痕列、版本 0）+ `hub_outbox` 插一条 `owner.status.changed`，同事务 | 投递失败由 `hub_outbox` 重试，不回收业务事务 | 契约 API-023、T-07 |
| 改档执行 | `wms_owner` 单行更新（四项 + `version+1` + 审计列），不写状态留痕、不写下行 | 无 | 契约 API-024 |
| 停用 / 启用执行 | `wms_owner` 单行更新（状态 + 版本 + 三列留痕）+ `hub_outbox` 一条事件，同事务 | 提交后异步：账号会话失效（见「账号与登录」），失败只告警不回滚 | 契约 API-025 / API-026、T-07 |
| 选择器与档案查询 | 只读单事务（默认隔离级别），不加锁 | 无 | 契约 API-020 至 API-022 |
| 受理结果查询 | 只读、`acceptNo` 主键定位 | 无 | 契约 API-027 |

并发控制：

| 路径 | 手段 | 锁类型与范围 | 冲突表现 |
|---|---|---|---|
| 改档 / 停用 / 启用 | 版本号乐观锁：`update wms_owner set ..., version = version + 1 where id = ? and version = ?` | 行级排他锁，范围是该 `id` 一行，由 `update` 自带的 `FOR UPDATE` 语义获得 | 受影响行数 0 → 回查区分：不存在 `NOT_FOUND_OWNER`、已停用且动作为改档 `STATE_INVALID`、版本不等 `CONFLICT_VERSION` |
| 同一 `creditCode` 并发建档 | 唯一索引 `uk_wms_owner_credit_code` 即串行化点 | 索引项级 | 后到者 `DataIntegrityViolationException` → `RULE_DUPLICATE_CREDIT_CODE` |
| 同一幂等键并发（≥10 次） | 幂等表唯一键 `(scope_key, api_code, idempotency_key)` + `on conflict do nothing` 占位，撞键回读首次记录 | 索引项级 | 首次成功者回放，其余取同一 `acceptNo`；占位窗口内返回 `IDEMPOTENT_IN_PROGRESS` |
| 同一档案并发 10 次同键停用 | 幂等去重先挡住重复请求；即便穿透也由版本号与触发器只放行一次迁移 | 行级 | 档案 1 条状态、留痕 1 份 |

**明确不做**：库存与库位的行锁方案（`SELECT ... FOR UPDATE`）不在本变更范围；货主档案**不使用** Redis 锁——它是低频主数据，`version` 乐观锁 + 唯一索引已足够，引入 Redis 锁会让「Redis 不可用」变成停用货主的阻断条件。也没有任何 `lock_id` 生成，`22` 3.3 的库存锁定链与本变更无关。

### 幂等与异步受理

**结论：复用既有受理机制与既有表，不新建受理表。**

复用条件（四项全部满足才允许复用，缺一项就必须新建独立受理表）：

| 条件 | 本变更的取值 | 说明 |
|---|---|---|
| 作用域键 | 常量 `HUB` | 契约 1.3 已定：运营端主数据接口的幂等作用域退化为「端 + 接口 + 键」，与既有运营端受理作用域一致；`scope_key` 不掺入货主，否则幂等键会被拆散 |
| 接口号 | `API-023`、`API-024`、`API-025`、`API-026` | 与商品档案既有接口号区间（001 至 020）不重叠，`(scope_key, api_code, idempotency_key)` 唯一键在两张业务之间天然隔离 |
| 受理时状态快照 | `ACTIVE`（建档、改档、停用）、`DISABLED`（启用） | `ck_hub_archive_acceptance_snapshot` 的闭集 `DRAFT/PENDING/ACTIVE/REJECTED/DISABLED` 已含这两个值，**不需要改约束** |
| 清理策略 | 受理记录不清理（业务留痕，INV-18）；幂等记录按 `expires_at` 24 小时清理 | 契约 1.3「清理不得早于业务唯一键失效」：清理后同键重提仍被 `uk_wms_owner_credit_code` 挡住 |

复用的具体做法：

- 受理与幂等机制上移到 `masterdata.acceptance`（`AcceptanceService` / `IdempotencyService` / 补偿扫描 / 仓储），商品档案与货主档案各写自己的 `Command`，机制只有一份。
- `AcceptanceCommand` 增一个可空字段 `subjectOwnerId`（受理单的目标货主）：改档、停用、启用写目标货主，建档写空（受理时档案还不存在），商品档案侧恒为空，行为不变。该字段落到 `hub_archive_acceptance.subject_owner_id`，是 API-027 数据域收窄的唯一依据（`scope_key` 恒为 `HUB`，无法按域区分）。
- **接口隔离**：API-027 的查询 MUST 带 `api_code in ('API-023','API-024','API-025','API-026')` 谓词；商品档案的受理查询接口 MUST 带「`api_code` 落在商品档案既有接口号区间（001 至 020）」的谓词。两侧都不改契约，只是把「谁看得到谁」落到 SQL 谓词上，满足契约 1.6「货主档案的受理单不通过商品档案的受理查询接口暴露」。
- 数据域收窄：域为「全部」→ 只看 `scope_key='HUB'`；域为「指定列表」→ 追加 `subject_owner_id in (:ids)`，`subject_owner_id is null` 的建档受理单对列表域账号不可见（按契约与不存在同一响应 `NOT_FOUND_OWNER`）；域为空 → `FORBIDDEN_OWNER`。

幂等细节（与契约 1.3 逐项对齐，不自行改键或窗口）：

| 项 | 实现 |
|---|---|
| 键格式 | 1 至 128 位、`A-Za-z0-9._:-`，入口校验，缺失或非法 `PARAM_INVALID`。既有实现按 64 位校验、两列也是 `varchar(64)`，本变更按契约放宽到 128：校验与列宽同批改（`V20261140`），只放宽不收窄，历史记录原样可用 |
| 请求指纹 | `SHA-256(apiCode \n requestTarget \n 规范化请求体)`，不覆盖 `Authorization`、`traceId`、客户端时间戳（沿用既有实现） |
| 首次与重复 | 首次写 `PROCESSING` 并返回 `replayed=false`；重复返回同一 `acceptNo`、同一状态快照、`replayed=true`，不新建档案、不递增版本、不写第二条留痕 |
| 处理中 | 占位未完成时返回 `IDEMPOTENT_IN_PROGRESS` + `retryAfterMs`（按窗口剩余时间，最少 1 秒） |
| 同键异体 | 指纹不等返回 `IDEMPOTENT_KEY_CONFLICT`，错误体不回显首次结果 |
| 窗口与恢复 | 24 小时窗口；超过 300 秒仍 `PROCESSING` 由补偿扫描落 `FAILED`（错误码 `DEPENDENCY_UNAVAILABLE`），保证 60 秒轮询上限之外必然收敛 |

### 停用货主如何阻断单仓端新建业务

**结论：Hub 下行货主状态，单仓端读本地副本（`site_owner_status`）；Hub 不可达时按副本放行，但副本同步超过阈值即拒绝；副本无记录时拒绝。**

| 取舍 | 结果 | 理由 |
|---|---|---|
| 选择 | 下行 + 本地副本判定 | 新业务受理是现场热路径：同步查 Hub 会把 Hub、权限、主数据的任何抖动传染成「现场开不了单」，与 D18「外部流量与作业流量物理隔离」相反；停用是低频人工动作，一次下行的成本远低于每单一次远端调用。注意 `05`「现场停摆的代价大于权限短暂陈旧」只适用于权限缓存，货主停用属业务准入，不能照搬该口径 |
| 被否方案 | 单仓端受理时同步查 Hub | 除了可用性，它还必然新增一个 Site → Hub 的内部查询入口（本契约不新增接口），并把「主数据可用性」变成「单据受理可用性」的前置条件 |

事件与投递：

| 项 | 约定 |
|---|---|
| 事件名 | `owner.status.changed`（写入 `hub_outbox.event_type`） |
| 交换与路由键 | 交换沿用 `wms.archive.events`；路由键新增 `wms.owner.status`（配置项 `wms.owner.outbox.routing-key`，缺省即该值）。投递器按 `event_type` 前缀解析路由键：`SKU_*` → `wms.archive.sku`，`OWNER_*` → `wms.owner.status` |
| 载荷 | `{eventId, ownerId, ownerNo, status, action, changedAt, version}`；`eventId = ownerNo + "#" + version`（与既有 `ArchiveOutboxMessage.eventIdOf` 同一形态），不带手机号、信用代码等敏感字段 |
| 触发点 | 建档、停用、启用三条，与业务同事务写 `hub_outbox`（T-07）；改档不写（改档不改状态） |
| 投递保障 | 至少一次：投递器 `claim` 条件更新抢占，多副本只有一个实例发出；失败记 `FAILED` + 指数退避重投；不阻塞业务事务、不回滚已提交状态 |
| 消费幂等 | Site 按 `owner_id` 主键 upsert，且仅当 `source_version >= 现有 source_version` 才覆盖（乱序保护）；同一 `eventId` 重复投递不产生第二行 |
| 死信 | 消费抛异常 → `basicNack(requeue=false)` 进死信队列 `wms.owner.status.dlq`；DLQ 深度 > 0 即告警，按 `eventId` 重放。Hub 侧对 `FAILED` 超阈值的 outbox 记录告警，两侧都不静默 |
| 失效时延 | 正常路径 P95 ≤ 30 秒（投递间隔）加消费耗时；告警阈值 5 分钟。契约不承诺强一致，只承诺「停用后新建被拒」，时延口径写在此处 |

失败模式与降级（契约 1.10 标记为阻塞，此处定死）：

| 情形 | 单仓端受理的行为 | 理由 |
|---|---|---|
| 副本有记录且 `ACTIVE` | 放行 | 正常路径 |
| 副本有记录且 `DISABLED` | 拒绝，返回作业域的错误码（归单据域契约，本变更不定义） | 契约 1.7「以停用货主为对象的新建业务被拒」 |
| **Hub 不可达且副本在阈值内（`data_timestamp` 未超过 1 小时）** | **放行**，按副本中的最近一次权威状态判定 | 阈值内是「最近一次权威状态」，Hub 短时抖动不应让全仓停摆；阈值把「短暂陈旧」与「长期失联」分开 |
| **Hub 不可达且副本超过阈值（`data_timestamp` 超过 1 小时）** | **拒绝**，返回可重试错误并提示联系运营 | 长期失联下副本不再可信；放行等于给可能已停用的货主开单，代价是数据错误与冲销，拒绝只是可见阻塞，可人工兜底。阈值取 1 小时与告警阈值 5 分钟错开，避免同一时间窗内既告警又放行 |
| **副本无记录（新档未下行、消费链路断）** | **拒绝**，返回可重试错误 | 状态不可读时不放行、不「先写后补」（契约 1.2「降级」）；新货主在首次下行到达前没有可受理的业务，拒绝窗口是秒级 |
| 存量单据与任务 | 不判定，继续执行至终态 | spec「存量继续」：判定只在「新建受理」这一个入口，不在执行链路 |
| 开关关闭（`wms.owner.blocking.enabled=false`） | 全部放行 | 上线顺序控制用，默认 `false`，等副本覆盖率确认后再打开 |

本变更交付「副本 + 闸门组件」，闸门以单一入口 `OwnerStatusGate.assertCanAcceptNewBusiness(ownerId)` 暴露给 Site 各业务域受理调用；具体接线到哪张单据由单据域变更完成（该处的错误码属单据域契约）。闸门内部除状态判定外，还比较最近一次成功消费时间与 `wms.owner.blocking.stale-threshold`（缺省 1 小时）：超过阈值即拒绝，并记一条「货主状态陈旧」的拒绝日志供运维定位。契约 1.9 已补「货主状态变更」事件条目，两侧口径一致。

### 鉴权与数据域落点

**功能权限**

| 项 | 落点 |
|---|---|
| 权限点常量 | `com.huoling.wms.common.owner.OwnerPermissionCodes`（`READ="owner.hub.read"`、`WRITE="owner.hub.write"`）；Hub 侧 `masterdata.owner.domain.OwnerPermissionCodes` 只做别名，取值只有一处（沿用档案模块的做法） |
| 注解位置 | 控制器方法上 `@RequiresPermission`：API-021 / API-022 / API-027 用 `READ`；API-023 / API-024 / API-025 / API-026 用 `WRITE`。写入权限点校验在**受理入口**（同步），不在异步执行线程里（异步线程没有请求上下文） |
| 初始化脚本 | `R__hub_owner_permissions.sql`（repeatable）：插 `owner.hub.read`(id 2011)、`owner.hub.write`(id 2012)，`module='OWNER'`（对应 `24` 的 M01 货主）；绑定：主数据管理员两项，仓网管理员、权限管理员、计费专员、对账员、跨仓调度员各读一项（对齐 `05` 第 192 行「主数据」列） |
| API-020 权限点变更 | 由 `archive.hub.create` 改为 `owner.hub.read`。现有持有者只有主数据管理员角色（`R__hub_archive_permissions.sql` 第 44 行）；脚本追加一条幂等补授「凡持 `archive.hub.create` 的角色补 `owner.hub.read`」，把权限点变更对存量账号的影响在脚本层抹平，避免改造上线后代建页 403。`archive.hub.create` 本身保留（代建仍需要） |
| 权限管理员必须可读 | 否则无法开通货主端账号（API-020 的第二个用途），脚本强制绑定 |

**数据域（INV-19 的第二层、第三道门）**

- 解析：`OwnerAccessGuard` 按会话账号的 Hub 端角色合并 `role_scope`（沿用 `DataScopeMerger`），得到 `ownerDomain`（全部 / 指定列表 / 空）。域为空 → 直接 `FORBIDDEN_OWNER`，MUST NOT 回落成全部（`05` 第 3 节、INV-06）。
- 注入：域以 `OwnerScope` 值对象随查询方法签名下传，Mapper 统一走同一个 `owner_scope` 片段（`id in (:ids)` 或无条件），**业务代码不手写货主条件**。在货主主数据上，「注入 `owner_id`」退化为「注入被查对象集合 `id`」——因为表本身既是货主维度又是被查主体。
- 建档：要求域为「全部」，非全域直接 `FORBIDDEN_OWNER`（新建对象无法落入既有指定列表）。
- 越权表现：域外目标 → `NOT_FOUND_OWNER`（404，与不存在同一响应，不泄露存在性）；域为空或端标识不是 `HUB` → `FORBIDDEN_OWNER`（403）；无功能权限 → 身份模块 `AUTHZ_2001`（403）。
- 端标识：`/api/hub/owner-archives/**` 与 `/api/hub/owners` 的过滤器只注册 `HUB` 端白名单，货主端与单仓端令牌在第一道门即 401/403，不进入业务。

### 账号与登录

| 项 | 落点与手段 | 依据 |
|---|---|---|
| 开通货主端账号必须绑定存在的货主 | `AccountCommandService.create`：`client=OWNER` 时 `ownerId` 必填，且查 `wms_owner` 存在；`Account.create` 已有的「员工与货主二选一非空」校验保留。库层 `fk_wms_account_owner` 兜住「存在性」 | 契约 1.7、spec「账号必须绑定存在的货主」 |
| 绑定不可变 | 库层触发器 `wms_account_owner_immutable`：`before update` 时 `new.owner_id is distinct from old.owner_id` 即 `raise exception`（含空→值、值→空、值→另一值）；应用层 `AccountCommandService.update` 携带不同 `ownerId` 直接 `COMMON_9001`。换货主只能停用账号另开 | spec「绑定不可改」 |
| 停用货主拒绝登录 | `AuthService.login` 与 `AuthService.refresh` 在 `account.enabled()` 之后增加一次判定：账号 `ownerId` 非空时查货主状态，`DISABLED` → `AUTH_1003`（按「账号不可用」处置，不新增错误码）。续期口也必须查，否则旧刷新令牌能换出新访问令牌 | 契约 1.7、spec「停用拒绝登录」 |
| 已签发令牌下一次请求被拒 | `JwtAuthenticationFilter` 第四步之后增一步：`client == OWNER` 时查 `OwnerAccessCache.isUsable(accountId)`，不可用即 401 `AUTH_1003`。缓存是「账号 + 货主状态」布尔值，键含全局权限版本号（复用 `PermissionCache` 的版本键），TTL 60 秒兜底 | 契约 1.7「已签发令牌在下一请求被拒」 |
| 会话缓存的失效 | 停用/启用受理提交成功后（`afterCommit`）：`permissionCache.bumpVersion()` 使所有带版本号的键失效 → 下一次请求回源即拿到 `DISABLED`；同时按该货主下的账号逐个 `refreshTokenStore.revokeAll(accountId)` 撤销刷新令牌 | `19`「权限集缓存在服务端，用全局版本号失效」 |
| 失效时延 | 正常：下一次请求（版本号 bump 是同步的，键立即不命中）；兜底：Redis 不可用时按 TTL 60 秒；两条路径都远小于访问令牌有效期 | 契约 1.7 |
| 数据保留 | 停用不清账号、不清档案、不撤销角色，存量单据与任务不受影响 | spec「数据保留在库」 |

`Account` 的 `isInternal()`（按 `employeeId` 判员工/货主）本变更不新增调用点：货主绑定判定一律用 `ownerId != null`，避免引入 D03 禁止的货主类型分支。

### 错误码映射

契约 1.4 表逐行落地；不新增契约外错误码。异常类型：`OwnerRuleViolation`（kernel 抛，携带契约码，与 `SkuRuleViolation` 同构）、`OwnerContractException`（Hub 应用层抛，携带契约码与可选 `retryAfterMs`）。

| 错误码 | HTTP | `retryable` | 后端落点（异常类型与抛出点） | 契约位置 |
|---|---|---|---|---|
| `PARAM_INVALID` | 400 | false | 入口校验（字段级、未知字段、`limit` / `cursor` / 请求体上限）、已废弃参数 `ownerCode` / `name`（`details[].field` 给出旧名与新名）、`OwnerFields` 违规 | 同步 |
| `RULE_DUPLICATE_CREDIT_CODE` | 422 | false | `uk_wms_owner_credit_code` 唯一键冲突翻译；应用层预判只作提示 | API-027 结果 |
| `STATE_INVALID` | 409 | false | `OwnerStateMachine.transit` 抛 `OwnerRuleViolation`；触发器兜底 | API-027 结果 |
| `CONFLICT_VERSION` | 409 | true | `OwnerVersionRule.requireMatches` 前置 + `update ... where version = ?` 行数 0 | API-027 结果 |
| `NOT_FOUND_OWNER` | 404 | false | 仓储按 `ownerNo` + 货主域查不到（域外与不存在同一响应）；API-027 受理号不在域内 | API-022 同步、API-027 结果 |
| `FORBIDDEN_OWNER` | 403 | false | `OwnerAccessGuard`：端标识非 `HUB`、域为「指定列表」且为空、非全域建档 | 同步 |
| `IDEMPOTENT_IN_PROGRESS` | 409 | true | 受理入口判占位未完成，带 `retryAfterMs` | 同步 |
| `IDEMPOTENT_KEY_CONFLICT` | 409 | false | 受理入口指纹比对不等 | 同步 |
| `RATE_LIMITED` | 429 | true | 限流切面（运营端池 + 接口维度），带 `Retry-After` 与 `retryAfterMs` | 同步 |
| `DEPENDENCY_UNAVAILABLE` | 503 | true | `DataAccessException` 兜底、受理线程池拒绝、受理超时补偿 | 同步与 API-027 |
| `INTERNAL_ERROR` | 500 | false | 未捕获异常兜底，`message` 不含堆栈与 SQL | 同步与 API-027 |
| `AUTHZ_2001` | 403 | false | `PermissionInterceptor`（缺 `owner.hub.read` / `owner.hub.write`） | 身份模块既有 |
| `AUTH_1002` / `AUTH_1003` | 401 | false | 令牌无效；停用货主的账号访问（`JwtAuthenticationFilter` / `AuthService`） | 身份模块既有 |

取值定义：`com.huoling.wms.common.error.OwnerContractCodes`——新增两枚（`RULE_DUPLICATE_CREDIT_CODE`、`NOT_FOUND_OWNER`），其余引用既有 `ArchiveContractCodes` 常量而不复制字面量。`OwnerContractCodes.known(String)` 是闭集判定，供响应体拒绝契约外取值；不把两枚新码塞进 `ArchiveContractCodes`，否则商品档案接口也能发出货主档案的码。

### 可观测与兼容回滚

| 面 | 内容 |
|---|---|
| 关键指标 | `owner_archive_accept_total{api,result}`、`owner_archive_accept_processing`（仍在处理的受理单条数，定时刷新）、`owner_archive_write_duration`（P95 对 800 ms 预算）、`owner_status_downlink_lag_seconds`、`owner_status_dlq_depth`（本实例累计转入死信条数，非队列实时长度）、`site_owner_gate_denied_total{reason}`、`site_owner_gate_allowed_total`、`site_owner_cache_miss_total`（副本未命中专项）、`owner_scope_denied_total` |
| 日志字段 | `traceId`、`accountId`、`client=HUB`、`apiCode`、`acceptNo`、`ownerId`、`ownerNo`、`action`、`version`、`fromStatus → toStatus`、`reason`（截断 64 字）；不记信用代码与手机号全文 |
| 审计留痕 | 主数据无库位，D24 的三元组在本聚合退化为「人 + 端 + 对象标识」：`status_changed_by` / `status_changed_at` / `status_action` / `status_reason` 四列随聚合行更新，`hub_outbox` 同事务留一份事件；授权类动作仍写 `wms_authorization_log`。不伪造库位字段 |
| 灰度开关 | `wms.owner.archive.enabled`（新接口入口，默认 `true`）、`wms.owner.blocking.enabled`（单仓端受理闸门，默认 `false`，副本覆盖确认后打开）。选择器**不设**回退到账号派生的开关——契约 1.8 明确「不降级为账号派生数据」 |
| 上线顺序 | ① 脚本 1136–1139（表、清单、导入、外键）→ ② `R__hub_owner_permissions.sql` 与货主档案接口上线 → ③ 运营端补录并核对（`18` 清单）→ ④ 打开 Hub 下行 → ⑤ Site 升级并绑定队列（只消费不拦截）→ ⑥ 打开 `wms.owner.blocking.enabled` |
| 回滚 | 第 ⑥ 步回滚即关闭闸门（恢复「不拦截」）；第 ④ 步回滚即关闭下行（副本保留、不再更新，闸门此时必须同时关闭）。Flyway 脚本不反向执行，`wms_owner` 与补录清单保留，`site_owner_status` 可重建 |
| 存量数据影响 | 新增外键不改变既有行；`subject_owner_id` 可空、不回填；`site_owner_status` 上线为空表；权限点变更可能让历史手工授权的账号在 API-020 上 403——由脚本的幂等补授抹平，剩余组合在验收用例里逐项核对 |

### 回写登记

本章需要但契约与既有文档尚未覆盖的内容，一律登记、不自行扩写接口。

| 事项 | 影响 | 建议归口 | 是否阻塞实现 |
|---|---|---|---|
| 契约 1.9 与本章下行的口径 | 已在契约 1.9 补「货主状态变更」事件条目，两侧一致 | 已收口 | 否 |
| 商品档案的受理查询接口需加 `api_code` 归属谓词 | 否则货主档案受理单会经该接口暴露，违反契约 1.6 | 契约章 1.7 建议补一行登记该实现收窄 | 是（实现内收窄，不改契约语义） |
| 契约 1.7 写 `PUT /hub/api/accounts/{id}`，既有实现是 `PATCH /hub/api/accounts/{id}` | 方法不一致，账号修改契约与代码需要对齐 | 身份模块契约负责人 | 否（本变更不调用该接口） |
| 账号开通接口的请求体现无 `client` 与 `ownerId` 字段 | 契约 1.7 已补字段扩展清单（请求两个字段、响应四个字段），实现侧需同步请求体与响应体并重导开放接口文档 | 身份模块实现 | 是（否则货主端账号开不出来） |
| 作业域需要「以停用货主新建业务被拒」的错误码 | 本章只交付闸门，错误码属单据域 | 单据域契约负责人 | 否（接线在单据域变更） |
| D24 三元组在无库位的主数据上退化为「人 + 端 + 对象标识」 | 需在 `00` D24 或 `21` 补一句口径，避免后续主数据聚合各自解释 | `00` 维护方（需回写 00） | 否 |
| `18` 的「货主档案导入」清单需按四项必填与信用代码唯一重写 | 迁移脚本第 2、3 步的补录口径依赖它 | `18` 维护方 | 否 |
| `Account.isInternal()` 与 D03「代码禁止 `isInternal`」的字面冲突 | 本变更新增代码不用该方法，但规则表述需澄清（账号绑定判定不算货主类型分支） | `00` / `05` 维护方 | 否 |
| `pg_trgm` 扩展需目标实例可用 | 缺失则模糊筛选索引建不出来，脚本第 1 步即失败 | 部署 / `18` 环境前置 | 否（可退化为全表扫描，但需显式确认） |

## API 接口契约

本章是本次变更的前后端唯一契约源。编号规则：既有商品档案变更已占用 001 至 020，本次新增自 `API-021` 起顺序分配，不复用旧号；`API-020` 为**同一接口的改造**，沿用原号并取代同号旧条目（旧条目在数据来源、检索参数、权限点与响应字段上失效）。

### 1.1 基础约定

| 项 | 约定 |
|---|---|
| 基础路径与端分组 | 本变更接口全部落在运营端前缀 `/api/hub/**`；货主端 `/api/owner/**`、单仓端 `/api/site/**` 在本变更不新增接口；端之间路径不混用（D15） |
| 认证 | 运营端接口使用后台令牌，放请求头 `Authorization: Bearer <token>`；令牌由既有登录接口签发，有效期与刷新沿用身份模块既有口径，本变更不新增令牌类型 |
| 端与令牌的强校验 | `/api/hub/owner-archives/**` 与 `/api/hub/owners` 只接受 `HUB` 端令牌；携带货主端或单仓端令牌一律 403 `FORBIDDEN_OWNER`（端标识不是数据域判定的一部分，不因令牌有效而降低权限） |
| 错误响应结构 | 一个结构，不按端分叉：`code`、`message`、`traceId`、`retryable` 四个必填；`retryAfterMs`、`details` 按需。`code` 取 1.4 表的符号码，成功码固定为字符串 `"0"`；`message` 只给人看，调用方只判 `code`。本变更在响应包上补齐 `details`（错误明细数组，元素为 `{field, reason}`） |
| 幂等 | 四个写接口一律读请求头 `Idempotency-Key`，细则见 1.3 |
| 分页 | 只用键集分页。参数 `cursor`（不透明字符串，服务端生成，调用方 MUST NOT 解析或自行构造）与 `limit`（整数，缺省 20，上限 100）；响应固定带 `nextCursor` 与 `hasMore`；游标不跨接口通用 |
| 排序与筛选 | 排序字段白名单 `ownerNo`、`ownerName`、`updateTime`，方向 `asc` / `desc`；多字段排序不提供（键集分页需要稳定单键）。白名单外取值返回 `PARAM_INVALID`，不静默回落到缺省排序 |
| 时间与金额 | 时间一律 UTC ISO-8601 带 `Z`（如 `2026-11-25T08:30:00Z`），响应不带本地时区；请求接受带偏移的时间但按 UTC 归一。本变更不涉及金额与数量，不出现定点数字段 |
| 枚举 | `status` 取 `ACTIVE`、`DISABLED`；`lastStatusChange.action` 取 `CREATE`、`DISABLE`、`ENABLE`。取值闭集，未知值返回 `PARAM_INVALID`，不静默降级 |
| 版本与兼容 | 破坏性变更在本文与 `proposal.md` 中标注 **BREAKING** 并与调用方同批上线；字段只增不删；本次唯一删除的参数是 API-020 的 `ownerCode` 与 `name`（改名后不再接受，传入即 `PARAM_INVALID`，不设过渡别名）；废弃字段的退出时间与删除同批，不保留双写 |
| 请求边界 | 单请求体上限 16 KB，超限在入口返回 `PARAM_INVALID`（`details[].field=body`）；`limit` 上限 100、`cursor` 长度上限 512；本变更不提供批量接口、上传与导出，因此不设批量条数与导出条数 |
| 超时与重试 | 写接口服务端预算 800 ms，查询接口 500 ms；客户端建议超时 1 s；写接口允许自动重试，最多 2 次，退避自 200 ms 起指数增长，必须复用同一 `Idempotency-Key`；查询接口允许重试 1 次；退避依据以 `Retry-After` 头与 `retryAfterMs` 为准，两者都给时以 `retryAfterMs` 为准 |
| 限流与熔断 | 限流维度为「端 + 接口」，本变更全在运营端池，与货主端池、移动作业池物理分离（D18）：外部货主流量打满不影响本组接口，反之亦然；阈值由部署配置提供，契约只承诺超限返回 429 `RATE_LIMITED` 并带 `Retry-After` 与 `retryAfterMs`。熔断只作用于权限与数据域判定这类依赖调用，不作用于货主档案事务：依赖不可用时返回 503 `DEPENDENCY_UNAVAILABLE`，契约里不存在「熔断中」状态 |
| 查询元信息 | 货主档案以 Hub 权威数据为准（D12），本变更不读副本、不做缓存降级，因此成功响应不带 `dataTimestamp`、不带数据来源标识；`dataTimestamp` 仅作为保留字段出现在错误体（降级类错误）中，正常查询 MUST NOT 伪造该字段。若后续引入副本，按 D19 在此行补「带 `dataTimestamp` 与来源标识」 |

### 1.2 鲁棒性约定

| 面 | 契约承诺 | 判定标准 |
|---|---|---|
| 输入校验 | 每个字段有类型、长度、取值范围或枚举闭集约束；请求体未知字段一律 `PARAM_INVALID`（不静默忽略，避免拼错字段被当成「没改」）；筛选参数只按白名单生效，未登记参数被忽略且不改变语义；`limit` 越界、`cursor` 超长、请求体超 16 KB 均在入口拒绝 | 逐字段有约束表；超限在入口返回 400，不进入业务逻辑，不产生档案与受理单 |
| 错误分类 | 十类固定：请求不合法、业务规则拒绝、未认证、无权限、目标不存在、状态/并发冲突、幂等处理中、限流、依赖不可用、系统错误。每码只归一类，类别决定 HTTP 与 `retryable` | 1.4 表逐行可核 |
| 统一错误体 | 所有 4xx/5xx 都带 `code`、`message`、`traceId`、`retryable`；`message` 不含堆栈、SQL、内部地址、表名；`retryable` 由错误码决定，不由 HTTP 推断 | 逐响应断言四字段；`message` 正则断言不含 `at ` / `select` / `http://` |
| 超时 | 每接口一个总预算（写 800 ms、查 500 ms）；依赖调用预算小于总预算，剩余预算不足时快速失败（`DEPENDENCY_UNAVAILABLE`），不发起新的依赖调用 | 注入依赖延迟超过预算：在预算内返回约定码，不产生半成品档案 |
| 重试 | 只有写接口允许自动重试，且 MUST 复用同一 `Idempotency-Key`；最多 2 次，退避自 200 ms 起；查询接口最多 1 次；非幂等动作（本变更没有非幂等动作）明确禁止自动重试 | 按契约重试到上限，档案仍只有 1 条、状态留痕仍只有 1 条 |
| 限流 | 维度「端 + 接口」，运营端独立池；超限 429 `RATE_LIMITED` + `Retry-After` + `retryAfterMs`；单货主/单账号超限只影响自身该接口 | 打满 API-021 不影响 API-023 至 API-027，也不影响货主端与移动作业通道（D18） |
| 熔断 | 只对权限与数据域判定等依赖调用熔断，不熔断货主档案的写入事务；业务校验失败不计入熔断；熔断期间只暴露 `DEPENDENCY_UNAVAILABLE` | 依赖故障时不产生「停用了一半」「建了档但没编号」的半成品 |
| 并发 | 改档、停用、启用必带 `version`，落后返回 409 `CONFLICT_VERSION`（可重试）；同一档案的重复状态迁移返回 409 `STATE_INVALID`（不可重试，刷新后按当前状态操作）；本变更无批量接口 | 并发冲突有确定返回码；两并发请求不会都成功且版本只前进一次 |
| 资源 | 请求体 16 KB、`limit` 上限 100、`cursor` 上限 512 字符；本变更无批量、上传、导出，故不设对应上限（明确不提供，避免前端假设）；端之间资源池隔离 | 超限在入口被拒；运营端打满不影响其他端 |
| 降级 | 权限与数据域判定不可用、目标货主状态不可读时一律拒绝写，不放行也不「先写后补」；查询不降级为副本（本变更是权威库查询），依赖不可用时返回 503 而不是 200 空列表；`dataTimestamp` 不得用于粉饰实时结果 | 权限判定依赖不可用 → 503；不得出现「200 + 空数组」掩盖故障 |

### 1.3 幂等约定

适用范围：四个写接口。查询接口天然幂等，不要求幂等键，且 MUST 无副作用。

| 项 | 约定 |
|---|---|
| 键的载体 | 统一请求头 `Idempotency-Key`；货主档案不是单据、没有外部单号，因此一律取前端生成的**客户端请求号**（D21 非单据类口径），不接受把 `ownerNo` 当幂等键 |
| 键的格式 | 1 至 128 位，字符集 `A-Za-z0-9._:-`，大小写敏感，不允许空格与中文；服务端只做格式校验，不解析业务含义；缺失或不合法 → 400 `PARAM_INVALID` |
| 键的作用域 | 「作用域 + 接口 + 键」三元唯一。本变更接口的作用域恒为 `HUB`（运营端），因为对象本身是货主档案、没有上级货主可挂：D21 的「货主 + 接口 + 键」在运营端主数据接口上退化为「端 + 接口 + 键」，与既有运营端受理作用域一致。同一键在不同接口、不同端之间互不影响 |
| 请求指纹 | 覆盖 HTTP 方法、路径、路径参数、请求体规范化结果；不覆盖 `Authorization`、`traceId`、客户端时间戳等只影响传输不影响语义的字段。只承诺「同键同指纹才算重复」，指纹算法归后端设计 |
| 首次与重复 | 首次返回 202、受理号与 `replayed=false`；重复返回 **同一受理号、同一 `status` 快照**，`replayed=true`；不新建档案、不重复递增版本、不重复写状态留痕 |
| 异步受理的「首次结果」 | 指首次受理响应（受理号 + 受理时间），不是执行结果；执行结果只走 API-027 |
| 处理中 | 首次尚未完成时重复到达，返回 409 `IDEMPOTENT_IN_PROGRESS` 并带 `retryAfterMs`；调用方 MUST 用原键重试，禁止换键 |
| 同键异体 | 同键但请求指纹不同 → 409 `IDEMPOTENT_KEY_CONFLICT`，首次结果不被覆盖；错误体不返回首次结果摘要（避免把另一张表单的数据回显给当前调用方） |
| 窗口 | 24 小时（与既有运营端受理窗口一致）。窗口过期后重复提交按新请求处理，但仍受业务唯一键约束：建档会因 `creditCode` 已存在返回 `RULE_DUPLICATE_CREDIT_CODE`，不会重复建档 |
| 状态存储 | 幂等记录持久化，进程重启与缓存故障不丢；记录内容为键、指纹、首次受理号、技术状态与过期时间；技术状态只用于去重，不进业务状态机，也不对前端展示为单据状态 |
| 并发一致 | 同一键并发（含不少于 10 次）只产生一条档案、一条状态留痕、一个受理号、一份响应体；去重手段（唯一约束 / 行锁 / 状态机守卫）归后端设计 |
| 恢复 | 处理中必须有超时兜底：超过最长处理时间（60 秒，与 `UI-F02` 对齐）仍无结果，重复请求返回可重试失败（`DEPENDENCY_UNAVAILABLE`）或进入人工核对，不得永久停在处理中 |
| 清理 | 清理不得早于业务唯一键失效；清理后同键再提交按新请求处理，仍受 `creditCode` 全局唯一与状态机约束 |

按操作类型的固定语义：

| 操作 | 幂等键 | 首次 | 重复 | 同键异体 | 对外承诺 |
|---|---|---|---|---|---|
| 建档（API-023） | 客户端请求号 | 202 + 受理号 | 202 + 同一受理号 + `replayed` | 409 | 只产生一条档案 |
| 改档（API-024） | 客户端请求号 | 202 + 受理号 | 202 + 同一受理号 + `replayed` | 409 | 版本只前进一次 |
| 停用 / 启用（API-025、API-026） | 客户端请求号 | 202 + 受理号 | 202 + 同一受理号 + `replayed` | 409 | 状态只迁移一次，留痕只一条 |
| 查询（API-020、API-021、API-022、API-027） | 无 | 200 | 同结果 | — | 无副作用 |

### 1.4 错误码表

| 错误码 | HTTP | 类别 | 触发条件 | 可重试 | 调用方处置 | 出现位置 |
|---|---|---|---|---|---|---|
| `PARAM_INVALID` | 400 | 请求不合法 | 必填缺失、长度/字符集/精度越界、枚举闭集外取值、请求体未知字段、`limit`/`cursor` 越界、请求体超限、`Idempotency-Key` 缺失或格式非法、传入已废弃参数 `ownerCode` 或 `name` | 否 | 按 `details[].field` 修正后重提 | 同步响应 |
| `RULE_DUPLICATE_CREDIT_CODE` | 422 | 业务规则拒绝 | 统一社会信用代码与既有档案重复（含并发提交时后到者） | 否 | 核对已有档案或改用正确主体，不重试 | API-027 受理结果 |
| `STATE_INVALID` | 409 | 状态/并发冲突 | 停用态改档；对已停用再停用；对可用再启用 | 否 | 刷新详情后按当前状态操作；改档需先启用 | API-027 受理结果 |
| `CONFLICT_VERSION` | 409 | 状态/并发冲突 | 请求 `version` 落后于当前版本 | 是 | 重新查询详情取最新 `version` 后按原键重提 | API-027 受理结果 |
| `NOT_FOUND_OWNER` | 404 | 目标不存在 | `ownerNo` 不存在，或存在但不在调用方货主域内（两者同一响应，不泄露存在性） | 否 | 修正编号或换有权限账号 | API-022 同步、API-027 受理结果 |
| `FORBIDDEN_OWNER` | 403 | 无权限 | 令牌端标识不是 `HUB`；会话货主域为「指定列表」且列表为空；货主域为「指定列表」时尝试建档 | 否 | 换用运营端账号或申请全域 | 同步响应 |
| `IDEMPOTENT_IN_PROGRESS` | 409 | 幂等处理中 | 同一幂等键的首次请求仍在处理 | 是 | 用**原键**按 `retryAfterMs` 重试 | 同步响应 |
| `IDEMPOTENT_KEY_CONFLICT` | 409 | 幂等冲突 | 同一幂等键但请求指纹不同 | 否 | 换新键重新提交；首次结果不变 | 同步响应 |
| `RATE_LIMITED` | 429 | 限流 | 运营端该接口超过配额 | 是 | 按 `Retry-After` / `retryAfterMs` 退避 | 同步响应 |
| `DEPENDENCY_UNAVAILABLE` | 503 | 依赖不可用 | 权限或数据域判定依赖不可用；受理执行超时兜底；受理通道饱和 | 是 | 退避重试（写接口用原键），不改为查空结果 | 同步响应 |
| `INTERNAL_ERROR` | 500 | 系统错误 | 未捕获异常 | 否 | 带 `traceId` 报障 | 同步响应与 API-027 受理结果 |

补充说明：

- 功能权限不足（持有的角色不含 `owner.hub.read` 或 `owner.hub.write`）返回 403，错误码取身份模块既有的 `AUTHZ_2001`，不在本表重复定义，也不新增码；本表的 `FORBIDDEN_OWNER` 只表达数据域与端标识问题。
- 未认证（令牌缺失、过期、签名非法）返回 401，错误码沿用身份模块既有 `AUTH_1002`，不进入本表。
- 异步受理后的失败不产生 HTTP 错误，但错误码取自本表，出现在 API-027 的 `errorCode`。

### 1.5 字段结构与枚举

请求与响应统一使用下表英文名，页面与服务端不得另起同义字段；标识一律用字符串，不用数字（避免大整数在 JS 侧丢精度）。

| 字段 | 类型与约束 | 出现位置 |
|---|---|---|
| `ownerId` | 字符串，十进制数字；货主在平台内的标识，只用于既有契约键（商品档案代建请求、账号开通），不用于寻址 | 列表与详情响应、API-020 响应、API-027 结果 |
| `ownerNo` | 字符串，8 至 32 位，字符集 `[A-Z0-9]`，以 `OW` 开头；系统生成、全局唯一、不可变（停用与启用都不变） | 列表与详情响应、路径参数、筛选、API-020 响应 |
| `ownerName` | 字符串，1 至 128 字，允许重复 | 请求体、筛选、响应 |
| `contactName` | 字符串，1 至 64 字 | 请求体、响应 |
| `contactPhone` | 字符串，6 至 32 位，仅允许数字与 `+ - ( )` 与空格；不做号段校验 | 请求体、响应 |
| `creditCode` | 字符串，固定 18 位，大写字母（不含 `I`、`O`、`S`、`V`、`Z`）与数字；全局唯一；不校验校验位 | 请求体、筛选、响应 |
| `status` | 枚举 `ACTIVE`（可用）、`DISABLED`（停用） | 响应、筛选、写接口受理快照 |
| `version` | 整数，非负；每次成功写入递增 | 请求体（改档、停用、启用）、响应 |
| `reason` | 字符串，1 至 200 字；停用与启用的原因，必填 | 请求体 |
| `lastStatusChange` | 对象，最近一次状态变更：`action`（`CREATE` / `DISABLE` / `ENABLE`）、`reason`（`CREATE` 时为空）、`operator`（账号名）、`changedAt`（UTC 时间） | 详情响应 |
| `createdBy`、`createdTime`、`updatedBy`、`updateTime` | 字符串与 UTC 时间；创建与最后修改的留痕（D24） | 详情响应（列表只返回 `createdTime`、`updateTime`） |
| `items`、`nextCursor`、`hasMore` | 数组、不透明字符串、布尔；列表与选择器分页响应 | API-021、API-020 响应 |
| `acceptNo` | 字符串，受理号；每次受理唯一 | 202 响应、API-027 路径 |
| `replayed` | 布尔，`true` 表示本次是同一幂等键的重复请求、返回首次受理号；首次为 `false` | 202 响应 |
| `acceptStatus` | 枚举 `PROCESSING`、`SUCCEEDED`、`FAILED` | API-027 响应 |
| `result` | 对象，受理完成后的结果摘要；未完成为空。货主档案的结果摘要字段为 `ownerId`、`ownerNo`、`status`、`version` | API-027 响应 |
| `errorCode`、`errorMessage` | 字符串；受理失败时的错误码（取自 1.4 表）与说明 | API-027 响应 |
| `sortBy`、`sortDir` | 见 1.1「排序与筛选」 | 列表查询参数 |
| `updatedFrom`、`updatedTo` | UTC 时间，按最后修改时间过滤，闭区间 | 列表查询参数 |

状态枚举只有 `ACTIVE`、`DISABLED` 两值，MUST NOT 用它表达合同有效性；响应中不出现任何合同、费率或计费字段（`D33` 归计费域判定）。

### 1.6 写接口统一响应（受理票据）

四个写接口（API-023、API-024、API-025、API-026）成功一律返回 HTTP `202`，响应体只有受理票据，不含执行结果：

```json
{
  "code": "0",
  "message": "ok",
  "data": { "acceptNo": "AC20261125000001", "status": "ACTIVE", "replayed": false },
  "traceId": "tr-0af76519",
  "retryable": false
}
```

- `status` 是受理时的**业务状态快照**，不是执行结果：建档为新建档的初始状态 `ACTIVE`；改档与停用为当前 `ACTIVE`；启用为当前 `DISABLED`。调用方 MUST NOT 用它判断成功与否。
- 受理号只用于查询：结果（含 `ownerNo`、`version`、最终 `status`）由 API-027 返回；建议轮询间隔 3 秒，最长 60 秒，超时保留受理号并给查询入口（`12a` `UI-F02`）。
- 受理后的失败不走 HTTP 错误：由 API-027 的 `acceptStatus=FAILED` 与 `errorCode` 返回，错误码仍取自 1.4 表。
- 复用既有的运营端受理机制，但作用域与接口号独立：货主档案的受理单只能在 API-027 查到，不通过商品档案的受理查询接口暴露。

### 1.7 跨接口约束（既有接口的行为收窄）

以下接口的完整契约仍在其所属模块，本表只登记本变更带来的、调用方可见的行为变化；不新增错误码，沿用其所属模块既有码。

| 既有接口 | 本变更带来的收窄 | 调用方可见结果 |
|---|---|---|
| `POST /hub/api/accounts`（开通账号） | 请求体扩展两个字段：`client`（`HUB` / `OWNER`，必填）与 `ownerId`（十进制字符串，`client=OWNER` 时必填，`client=HUB` 时不得出现）；`client=OWNER` 时该货主档案必须存在 | 400（`COMMON_9001`），不创建账号 |
| `GET /hub/api/accounts` 与详情响应 | 响应扩展 `client`、`ownerId`、`ownerNo`、`ownerName` 四个字段，后两项由服务端按 `ownerId` 关联货主档案取得；非货主端账号四项为空 | 前端据此渲染「所属货主」列与只读字段；无字段时显示「—」，不显示裸标识 |
| `PUT /hub/api/accounts/{id}`（修改账号） | 已绑定货主的账号不允许改 `ownerId`；携带不同 `ownerId` 即拒绝 | 400（`COMMON_9001`），其余字段仍可改 |
| `POST /hub/api/auth/login`（登录） | 登录按账号所在端签发令牌并只加载该端角色（`ownerId` 有值为货主端，否则为运营端）；账号所属货主档案为 `DISABLED` 时拒绝登录；已签发令牌在下一请求被拒（同一判定，返回 401 / `AUTH_1003`，按「账号不可用」处置） | 登录失败提示联系管理员；数据保留在库，存量单据与任务照常走完 |
| `GET /api/hub/owners`（API-020） | 见下条目的改造：数据来源换成货主档案、检索参数改名、权限点变更、响应新增 `ownerNo` | 本章 API-020 条目取代旧条目 |
| 作业受理链路（各业务域） | 以停用货主为对象的新建业务被拒；存量单据与任务不阻断 | 该判定与错误码归各业务域契约，本契约不新增接口 |

货主端不提供 `/hub/api/auth/me` 与 `/hub/api/auth/logout` 两个入口：`/hub/api/*` 只放行运营端令牌，货主端的登录态由刷新令牌与服务端失效共同维持，退出登录不在本变更范围。该口径避免把外部货主流量引到运营端网关。

### 1.8 API 条目

#### API-020 运营查询可用货主（改造，**BREAKING**）

| 项 | 内容 |
|---|---|
| 方法与路径 | `GET /api/hub/owners`（路径不变） |
| 端与角色 | 运营端；主数据管理员（代建时选定归属）、权限管理员（开通货主端账号时选定货主），`05` 中「主数据」列为「读」的其余角色同样可查 |
| 鉴权 | 后台令牌（`HUB`）；功能权限 `owner.hub.read`（**由 `archive.hub.create` 变更**）；数据域为会话货主域：域为「全部」返回全量，域为「指定列表」只返回域内货主，域为空返回 `FORBIDDEN_OWNER` |
| 请求 | 查询参数：`ownerNo`（精确匹配，1 至 32 位）、`ownerName`（模糊匹配，1 至 64 位），两者同时给出取交集；`sortBy`（`ownerNo` / `ownerName`，缺省 `ownerNo`）、`sortDir`（缺省 `asc`）、`cursor`、`limit`（缺省 20，上限 100）。**旧参数 `ownerCode`、`name` 不再接受**，传入即 `PARAM_INVALID`（`details[].field` 给出旧名与新名的对应） |
| 响应 | 200，`items` 元素为 `{ownerId, ownerNo, ownerName}`，仅含 `status=ACTIVE` 的货主；另有 `nextCursor`、`hasMore`。无可用货主时返回空数组（不伪造条目）；不返回 `dataTimestamp`（读权威库，无副本） |
| 错误码 | `PARAM_INVALID`、`FORBIDDEN_OWNER`、`RATE_LIMITED`、`DEPENDENCY_UNAVAILABLE`、`INTERNAL_ERROR`（另含身份模块 403 / 401 两类） |
| 超时预算 | 服务端 500 ms；客户端建议 1 s，最多重试 1 次 |
| 限流 | 运营端 + 接口；与货主端、移动作业端分池（D18）；超限 429 + `Retry-After` |
| 降级 | 不降级为账号派生数据；权限或数据域判定不可用返回 503，不返回空列表 |
| 并发冲突 | 不适用（只读） |
| 幂等 | 不适用（天然幂等、无副作用）；不要幂等键 |
| 分页/排序/筛选 | 键集分页；排序白名单 `ownerNo`、`ownerName`；筛选见「请求」行 |
| 验收用例 | 编码精确筛选；展示名模糊筛选；翻页；停用货主不出现；无档案时返回空数组；`ownerCode` 传参被拒；域外货主不出现；权限点不足被拒；依赖不可用返回 503；打满限流后本端其他接口不受影响 |
| 关联不变量 | INV-06、INV-19、D18、D40（不做仓级授权：不按仓过滤货主）、D03（无内部/外部货主分支，响应不带任何货主类型字段） |

#### API-021 运营查询货主档案列表

| 项 | 内容 |
|---|---|
| 方法与路径 | `GET /api/hub/owner-archives` |
| 端与角色 | 运营端；`05`「主数据」列为读的六个角色（主数据管理员、仓网管理员、权限管理员、计费专员、对账员、跨仓调度员） |
| 鉴权 | 后台令牌（`HUB`）；功能权限 `owner.hub.read`；数据域为会话货主域（全部→全量；指定列表→仅域内；列表为空→403 `FORBIDDEN_OWNER`） |
| 请求 | 查询参数：`ownerNo`（精确）、`ownerName`（模糊）、`creditCode`（精确，18 位）、`status`（`ACTIVE` / `DISABLED`）、`updatedFrom`、`updatedTo`（UTC，闭区间）、`sortBy`（`ownerNo` / `ownerName` / `updateTime`，缺省 `ownerNo`）、`sortDir`（缺省 `asc`）、`cursor`、`limit`（缺省 20，上限 100）。四类筛选可任意组合，组合取交集 |
| 响应 | 200，`items` 元素含 `ownerId`、`ownerNo`、`ownerName`、`contactName`、`contactPhone`、`creditCode`、`status`、`version`、`createdTime`、`updateTime`；`nextCursor`、`hasMore`；不带 `dataTimestamp` |
| 错误码 | `PARAM_INVALID`、`FORBIDDEN_OWNER`、`RATE_LIMITED`、`DEPENDENCY_UNAVAILABLE`、`INTERNAL_ERROR` |
| 超时预算 | 服务端 500 ms；客户端 1 s，最多重试 1 次 |
| 限流 | 运营端 + 接口，独立池，超限 429 + `Retry-After` |
| 降级 | 不降级为副本（权威库查询）；依赖不可用返回 503，不返回空列表、不返回旧快照 |
| 并发冲突 | 不适用（只读） |
| 幂等 | 不适用（天然幂等、无副作用） |
| 分页/排序/筛选 | 键集分页，游标锚在排序键与 `ownerNo` 组合上，翻页期间新增档案不导致跳行或重复行；筛选见「请求」行 |
| 验收用例 | 四类筛选各一条；组合筛选一条；翻页两条（含新增档案后翻页不跳行）；停用态可被筛出；域外档案不出现；`sortBy` 非白名单被拒；`limit` 超上限被拒；依赖不可用返回 503；限流一条 |
| 关联不变量 | INV-19、INV-06、D18、D40 |

#### API-022 运营查询货主档案详情

| 项 | 内容 |
|---|---|
| 方法与路径 | `GET /api/hub/owner-archives/{ownerNo}` |
| 端与角色 | 运营端；同 API-021 的读角色 |
| 鉴权 | 后台令牌（`HUB`）；功能权限 `owner.hub.read`；数据域为会话货主域，目标 `ownerNo` 不在域内按不存在处理 |
| 请求 | 路径参数 `ownerNo`（8 至 32 位、`[A-Z0-9]`，不合法即 `PARAM_INVALID`）；无请求体、无查询参数 |
| 响应 | 200，返回 API-021 的全部字段外加 `createdBy`、`updatedBy`、`lastStatusChange`（`action` / `reason` / `operator` / `changedAt`）；`lastStatusChange` 在档案创建后必有值 |
| 错误码 | `PARAM_INVALID`、`NOT_FOUND_OWNER`、`FORBIDDEN_OWNER`、`RATE_LIMITED`、`DEPENDENCY_UNAVAILABLE`、`INTERNAL_ERROR` |
| 超时预算 | 服务端 500 ms；客户端 1 s，最多重试 1 次 |
| 限流 | 运营端 + 接口，超限 429 |
| 降级 | 不降级；依赖不可用 503 |
| 并发冲突 | 不适用（只读） |
| 幂等 | 不适用（天然幂等、无副作用） |
| 分页/排序/筛选 | 不适用（单对象查询） |
| 验收用例 | 命中详情；编号格式非法被拒；不存在返回 404；域外编号返回 404（与不存在同一响应）；停用货主详情含 `lastStatusChange.reason`；无功能权限被拒 |
| 关联不变量 | INV-19、INV-06、D24（状态变更三元组可查） |

#### API-023 运营建档货主档案

| 项 | 内容 |
|---|---|
| 方法与路径 | `POST /api/hub/owner-archives` |
| 端与角色 | 运营端；主数据管理员（`05`「主数据」列为写） |
| 鉴权 | 后台令牌（`HUB`）；功能权限 `owner.hub.write`；数据域为会话货主域，**要求域为「全部」**（新建对象无法落入既有指定列表），否则 403 `FORBIDDEN_OWNER` |
| 请求 | 请求体四项全部必填：`ownerName`（1 至 128 字）、`contactName`（1 至 64 字）、`contactPhone`（6 至 32 位，数字与 `+ - ( )`、空格）、`creditCode`（18 位，大写）。`null`、空串、全空格一律按缺失处理；未知字段一律 `PARAM_INVALID`；请求体不含 `ownerNo`、`status`、`version`（由系统生成或置初值，传入即未知字段被拒） |
| 响应 | 202，受理票据 `{acceptNo, status: "ACTIVE", replayed}`；生成的 `ownerNo`、`ownerId`、`version` 由 API-027 返回。建议 3 秒轮询，最长 60 秒 |
| 错误码 | 同步：`PARAM_INVALID`、`FORBIDDEN_OWNER`、`IDEMPOTENT_IN_PROGRESS`、`IDEMPOTENT_KEY_CONFLICT`、`RATE_LIMITED`、`DEPENDENCY_UNAVAILABLE`、`INTERNAL_ERROR`；受理结果：`RULE_DUPLICATE_CREDIT_CODE`、`INTERNAL_ERROR` |
| 超时预算 | 服务端 800 ms（受理提交）；客户端 1 s，最多重试 2 次且必须用同一幂等键 |
| 限流 | 运营端 + 接口，超限 429 + `Retry-After` |
| 降级 | 不降级写入：权限与数据域判定不可用时拒绝受理（503），不「先建后校验」 |
| 并发冲突 | 无版本号（新建）；同一 `creditCode` 并发提交只有一条成功，另一条在受理结果返回 422 |
| 幂等 | 键来源 `Idempotency-Key` 取客户端请求号；作用域 `HUB` + API-023 + 键；窗口 24 小时；重复返回同一受理号 + `replayed=true`；处理中 409 + `retryAfterMs`；同键异体 409 且首次结果不变 |
| 分页/排序/筛选 | 不适用 |
| 验收用例 | 首次建档成功且编号全局唯一；四项必填逐项缺失各一条；名称与已有档案相同但信用代码不同 → 成功（同名不阻断）；信用代码重复 → 422 且不产生第二条档案；重复提交（同键同体）返回同一受理号；十次并发同键只产生一条档案与一条留痕；同键异体 409；请求体未知字段 400；`ownerName` 超长 400；限流一条 |
| 关联不变量 | INV-18（不物理删除，停用表达）、INV-19、D03（无内部/外部货主分支）、D21、D40（建档即可见全仓网，不按仓授权） |

#### API-024 运营改档货主档案

| 项 | 内容 |
|---|---|
| 方法与路径 | `PUT /api/hub/owner-archives/{ownerNo}` |
| 端与角色 | 运营端；主数据管理员 |
| 鉴权 | 后台令牌（`HUB`）；功能权限 `owner.hub.write`；数据域为会话货主域，目标不在域内按不存在处理 |
| 请求 | 路径参数 `ownerNo`；请求体为全量替换：`ownerName`、`contactName`、`contactPhone`、`creditCode`（四项必填，语义同 API-023）加 `version`（整数，非负，必填）。`ownerNo`、`status` 不在请求体内（编号不可变、状态走独立动作）；未知字段一律 `PARAM_INVALID` |
| 响应 | 202，受理票据 `{acceptNo, status: "ACTIVE", replayed}`；结果（含递增后的 `version`）由 API-027 返回 |
| 错误码 | 同步：`PARAM_INVALID`、`FORBIDDEN_OWNER`、`IDEMPOTENT_IN_PROGRESS`、`IDEMPOTENT_KEY_CONFLICT`、`RATE_LIMITED`、`DEPENDENCY_UNAVAILABLE`、`INTERNAL_ERROR`；受理结果：`NOT_FOUND_OWNER`、`STATE_INVALID`（停用态不可改档）、`CONFLICT_VERSION`、`RULE_DUPLICATE_CREDIT_CODE` |
| 超时预算 | 服务端 800 ms；客户端 1 s，最多重试 2 次，同一幂等键 |
| 限流 | 运营端 + 接口，超限 429 |
| 降级 | 不降级写入；权限与数据域判定不可用 503 |
| 并发冲突 | `version` 乐观比对：落后返回 409 `CONFLICT_VERSION`（可重试：刷新详情后按原键重提）；同一档案并发两笔改档只有一笔成功，版本只前进一次 |
| 幂等 | 键来源 `Idempotency-Key` 取客户端请求号；作用域 `HUB` + API-024 + 键；窗口 24 小时；重复返回同一受理号 + `replayed=true`；处理中 409 + `retryAfterMs`；同键异体 409 |
| 分页/排序/筛选 | 不适用 |
| 验收用例 | 改档成功后 `ownerNo` 不变、`version` 递增；版本落后 409；停用态改档 409 `STATE_INVALID`；信用代码改成与他档重复 422；重复提交同一受理号；十次并发同键版本只前进一次；同键异体 409；不存在编号 404（受理结果）；四项必填逐项缺失 400 |
| 关联不变量 | INV-19、INV-18、D21；编号不可变（spec「编号不随改档变化」） |

#### API-025 运营停用货主档案

| 项 | 内容 |
|---|---|
| 方法与路径 | `POST /api/hub/owner-archives/{ownerNo}/disable` |
| 端与角色 | 运营端；主数据管理员 |
| 鉴权 | 后台令牌（`HUB`）；功能权限 `owner.hub.write`；数据域为会话货主域 |
| 请求 | 路径参数 `ownerNo`；请求体 `{reason, version}`，`reason` 必填（1 至 200 字，停用留痕的依据）、`version` 必填；未知字段一律 `PARAM_INVALID` |
| 响应 | 202，受理票据 `{acceptNo, status: "ACTIVE", replayed}`（`status` 为受理时快照，不是执行结果） |
| 错误码 | 同步：`PARAM_INVALID`、`FORBIDDEN_OWNER`、`IDEMPOTENT_IN_PROGRESS`、`IDEMPOTENT_KEY_CONFLICT`、`RATE_LIMITED`、`DEPENDENCY_UNAVAILABLE`、`INTERNAL_ERROR`；受理结果：`NOT_FOUND_OWNER`、`STATE_INVALID`（已停用）、`CONFLICT_VERSION` |
| 超时预算 | 服务端 800 ms；客户端 1 s，最多重试 2 次，同一幂等键 |
| 限流 | 运营端 + 接口，超限 429 |
| 降级 | 不降级写入；权限与数据域判定不可用 503（不允许「停用了一半」） |
| 并发冲突 | `version` 乐观比对；与改档并发时只有先到者成功，后到者 409 `CONFLICT_VERSION` |
| 幂等 | 键来源 `Idempotency-Key` 取客户端请求号；作用域 `HUB` + API-025 + 键；窗口 24 小时；重复返回同一受理号 + `replayed=true`（不重复写留痕）；处理中 409 + `retryAfterMs`；同键异体 409 |
| 分页/排序/筛选 | 不适用 |
| 验收用例 | 停用成功后状态为 `DISABLED` 且记录原因、操作人、时间；原因为空 400；重复停用 409 `STATE_INVALID`；同一请求重复提交返回同一受理号且留痕只一条；十次并发同键只停用一次；停用后选择器不再返回该货主（API-020）；停用后以该货主新建业务被拒（作业域用例，引用跨接口约束）；重启或迁移重跑后仍为 `DISABLED`（无自动恢复路径）；存量单据与任务继续（作业域用例） |
| 关联不变量 | INV-18（状态表达停用，不物理删除）、INV-19、D21、D24（停用留痕） |

#### API-026 运营重新启用货主档案

| 项 | 内容 |
|---|---|
| 方法与路径 | `POST /api/hub/owner-archives/{ownerNo}/enable` |
| 端与角色 | 运营端；主数据管理员（重新启用只能由人发起，无系统自动路径） |
| 鉴权 | 后台令牌（`HUB`）；功能权限 `owner.hub.write`；数据域为会话货主域 |
| 请求 | 路径参数 `ownerNo`；请求体 `{reason, version}`，两者必填；未知字段一律 `PARAM_INVALID` |
| 响应 | 202，受理票据 `{acceptNo, status: "DISABLED", replayed}`（受理时快照为停用态） |
| 错误码 | 同步：`PARAM_INVALID`、`FORBIDDEN_OWNER`、`IDEMPOTENT_IN_PROGRESS`、`IDEMPOTENT_KEY_CONFLICT`、`RATE_LIMITED`、`DEPENDENCY_UNAVAILABLE`、`INTERNAL_ERROR`；受理结果：`NOT_FOUND_OWNER`、`STATE_INVALID`（当前已可用）、`CONFLICT_VERSION` |
| 超时预算 | 服务端 800 ms；客户端 1 s，最多重试 2 次，同一幂等键 |
| 限流 | 运营端 + 接口，超限 429 |
| 降级 | 不降级写入；权限与数据域判定不可用 503 |
| 并发冲突 | `version` 乐观比对；落后 409 `CONFLICT_VERSION` |
| 幂等 | 键来源 `Idempotency-Key` 取客户端请求号；作用域 `HUB` + API-026 + 键；窗口 24 小时；重复返回同一受理号 + `replayed=true`；处理中 409 + `retryAfterMs`；同键异体 409 |
| 分页/排序/筛选 | 不适用 |
| 验收用例 | 启用成功后状态回 `ACTIVE`、`ownerNo` 不变、留痕含原因与操作人；原因为空 400；对可用货主启用 409 `STATE_INVALID`；重复提交同一受理号；十次并发同键只启用一次；启用后选择器重新出现该货主；无任何随系统动作自动恢复的路径（无定时任务接口可调用） |
| 关联不变量 | INV-19、D21、D24、D40 |

#### API-027 运营查询货主档案受理结果

| 项 | 内容 |
|---|---|
| 方法与路径 | `GET /api/hub/owner-archives/acceptances/{acceptNo}` |
| 端与角色 | 运营端；提交过 API-023 至 API-026 的角色（主数据管理员） |
| 鉴权 | 后台令牌（`HUB`）；功能权限 `owner.hub.read`；数据域为会话货主域：只可查询自己作用域内产生的受理单，域外与不存在同一响应 |
| 请求 | 路径参数 `acceptNo`（服务端生成的受理号，不透明字符串）；无请求体、无查询参数 |
| 响应 | 200，`acceptStatus`（`PROCESSING` / `SUCCEEDED` / `FAILED`）、`result`（成功时为 `{ownerId, ownerNo, status, version}`，未完成为空）、失败时 `errorCode` 与 `errorMessage`；成功响应不带 `dataTimestamp` |
| 错误码 | `PARAM_INVALID`、`NOT_FOUND_OWNER`（受理号不存在或不在域内）、`FORBIDDEN_OWNER`、`RATE_LIMITED`、`DEPENDENCY_UNAVAILABLE`、`INTERNAL_ERROR` |
| 超时预算 | 服务端 500 ms；客户端 1 s，建议 3 秒一次、最长 60 秒；最多重试 1 次 |
| 限流 | 运营端 + 接口；轮询按此维度计，超限 429（提示前端拉长间隔，不丢弃受理号） |
| 降级 | 受理存储不可用返回 503（可重试），不返回「成功」也不返回空结果 |
| 并发冲突 | 不适用（只读） |
| 幂等 | 不适用（天然幂等、无副作用） |
| 分页/排序/筛选 | 不适用（单对象查询） |
| 验收用例 | 处理中 → 成功链路（含 `ownerNo`）；处理中 → 失败链路（含 `RULE_DUPLICATE_CREDIT_CODE` / `CONFLICT_VERSION` / `STATE_INVALID` 各一条）；受理号不存在 404；受理号不属于本域 404；超过 60 秒仍处理中时的兜底（重复提交返回可重试失败或人工核对）；受理存储不可用 503 |
| 关联不变量 | INV-19；D20（结果只经查询接口，不在写接口返回） |

### 1.9 事件与回调

- 本变更新增一个下行事件「货主状态变更」（代码常量 `owner.status.changed`）：仅在建档、停用、启用时发出，不向货主端推送；载荷 `{eventId, ownerId, ownerNo, status, action, changedAt, version}`，`eventId = ownerNo + "#" + version`，消费方按 `eventId` 幂等，失败事件仍取自 1.4 错误码。除此之外不新增订阅与回调；受理结果只经 API-027 查询兜底，不提供结果推送。
- 不改动既有商品档案的下行事件；货主档案的状态不参与既有事件载荷。
- 「停用阻断以该货主为对象的新建业务」由上述下行事件承载：单仓端读本地副本判定，副本无记录拒绝，Hub 不可达且副本超过 1 小时未更新也拒绝。具体判定与降级见「后端设计」。

### 1.10 待确认与回写登记

| 事项 | 现状与影响 | 归口 | 是否阻塞本变更 |
|---|---|---|---|
| `22` 状态机总表缺货主档案行 | 本契约按 `ACTIVE` / `DISABLED` 两态落地，`22` 的八类对象需增一行（建议 S-09）并写禁止迁移（停用态直接改档为禁止项） | 设计文档维护方 | 否（契约已定两态） |
| `21` 枚举表缺货主档案状态 | 需登记一条枚举（可用、停用；代码 `ACTIVE` / `DISABLED`），并登记货主档案权限点两项 | 设计文档维护方 | 否 |
| `05` 缺货主档案权限点 | 需并入 `owner.hub.read`、`owner.hub.write` 并写入权限点初始化脚本；须明确权限管理员持「主数据读」，否则其无法开通货主端账号；另需补一句「货主档案接口按会话货主域收窄、建档要求全域」 | 权限设计方 | 否，但不回写则 API-020 的权限变更无依据 |
| `05` 权限点编码风格不一致 | `05` 正文示例为「域:资源:动作」（`inbound:receipt:create`），既有代码与 `archive.*` 七个点、本契约 `owner.hub.*` 均为点分「模块.资源.动作」 | `05` 维护方 | 否，但需统一一处说法 |
| `00` D21 释义需补一句 | 运营端主数据类接口的幂等作用域按「端 + 接口 + 键」，货主维度在主数据目录接口上退化为端 | `00` 维护方 | 否 |
| 身份模块契约需登记三条收窄 | 已在 1.7 登记字段扩展与三处行为（绑定存在货主、绑定不可改、停用拒绝登录与会话失效）；身份模块的正式契约需同步这三条与四个响应字段，复用既有 `COMMON_9001` 与 `AUTH_1003` | 身份模块契约负责人 | 否，但三处行为与四个字段 MUST 落入身份模块的正式契约 |
| 单仓端如何感知货主停用 | 已定：货主状态变更事件下行 + 单仓端本地副本判定，副本无记录或超 1 小时未更新且 Hub 不可达时拒绝，见「后端设计」 | 已收口 | 否 |
| 主数据类低频写是否豁免 D20 | 本变更按 D20 走 202 受理，但货主档案无下行、低频，异步收益有限；是否对主数据类写豁免属全局口径，留给下一版决策 | `00` 维护方 | 否（本变更已按 202 落地） |
| 信用代码校验位是否校验 | 本契约只校验长度与字符集；若后续要求校验 GB 32100 校验位，属输入加严，会拒掉原先通过的请求，需按破坏性变更登记 | 需求方 | 否 |
| 既有商品档案受理票据缺 `replayed` | 本变更在货主档案受理票据上引入 `replayed`，商品档案票据未带该字段；两处表达不一致 | 商品档案契约负责人 | 否（建议同批补齐或另立变更） |
| 既有档案错误体缺 `details` | 本变更为字段级错误引入 `details`，既有商品档案错误体未带 | 商品档案契约负责人 | 否（只增字段，向后兼容） |

### 1.11 契约验收

| 验收项 | 可判定标准（逐条勾选） |
|---|---|
| 完整性 | 8 个条目（API-020 改造 + API-021 至 API-027）均给出路径、方法、请求结构、响应结构、错误码、鉴权（功能权限 + 数据域）、分页与幂等；4 个写接口均有条目级幂等键、作用域、窗口与重复表现 |
| 必填四项 | API-023 与 API-024 对名称、联系人、联系电话、信用代码逐项缺一提交，各返回 400 `PARAM_INVALID` 且 `details[].field` 命中该字段；不产生档案 |
| 编号规则 | 建档受理结果为 `ownerNo`，形态匹配 `^OW[A-Z0-9]{6,30}$`；改档、停用、启用各一次后 `ownerNo` 不变；两笔并发建档得到两个不同编号 |
| 名称可重复 | 同名 + 不同信用代码两次建档均成功，列表中两条记录靠 `ownerNo` 区分 |
| 信用代码唯一 | 重复信用代码返回 422 `RULE_DUPLICATE_CREDIT_CODE` 且不产生第二条档案；十次并发同码只有一条成功；大小写不一致（小写提交）先被 400 拒绝 |
| 两态且不代表合同 | `status` 只出现 `ACTIVE` / `DISABLED`；未知值 400；构造「货主可用但合同未生效」，货主档案仍返回 `ACTIVE`，契约响应中不出现任何合同字段 |
| 停用边界 | 停用后 API-020 不再返回该货主；以该货主新建业务被拒（作业域用例）；执行中的单据与任务可走到终态（作业域用例）；停用原因、操作人、时间可在 API-022 的 `lastStatusChange` 查到 |
| 停用不自动恢复 | 重启、迁移重跑、定时任务执行后 API-022 仍返回 `DISABLED`；契约中不存在任何无需人操作的启用入口 |
| 重新启用 | 仅有 `owner.hub.write` 的账号可启用；启用后状态 `ACTIVE`、`ownerNo` 不变、留痕含原因与操作人；无写权限账号 403 |
| 选择器数据来源 | API-020 返回条目全部来自货主档案（停用货主不出现，无档案时返回空数组）；断掉货主端账号数据后仍能查到货主（证明不再从账号派生） |
| 选择器字段 | API-020 条目含 `ownerNo` 与 `ownerName`，`ownerNo` 可精确检索、`ownerName` 可模糊检索；`ownerCode` 传参被 400 拒绝 |
| 账号绑定约束 | `client=OWNER` 未选货主或选不存在的货主 → 400 且不建账号；已绑定账号改 `ownerId` → 400；绑定后重新登录，归属货主未变 |
| 停用货主登录 | 货主停用后该账号登录被拒（`AUTH_1003`）；已登录会话下一次请求被拒；数据仍在库，存量单据与任务继续 |
| 货主端越权 | 货主端令牌调用 API-021 至 API-027 全部返回 403 `FORBIDDEN_OWNER`；货主端菜单不出现管理入口 |
| 无权限 | 不含 `owner.hub.read` / `owner.hub.write` 的运营端角色调用对应接口返回 403（`AUTHZ_2001`），不返回空数据 |
| 数据域 | 域为「指定列表」的账号：列表只返回域内、域外详情返回 404、域外写操作按不存在处理；指定列表为空时 403；建档在非全域账号上 403 |
| 幂等 | 每个写接口十次并发同键：档案 1 条、状态留痕 1 条、受理单 1 条、受理号一致、响应体一致；同键异体 409 且首次结果不变；处理中 409 + `retryAfterMs` |
| 幂等过期 | 24 小时窗口后同键重提按新请求处理：建档被 422 拒绝（业务唯一键生效），不产生第二条档案 |
| 错误码 | 1.4 表每行至少一个触发用例；所有 4xx/5xx 含 `code`、`message`、`traceId`、`retryable`；`retryable` 与类别一致；`message` 不含堆栈、SQL、表名、内部地址 |
| 超时与重试 | 注入权限判定延迟超过预算：在 800 ms / 500 ms 内返回 `DEPENDENCY_UNAVAILABLE`，不产生半成品档案；按契约重试到上限，档案仍 1 条；查询接口自动重试超过 1 次判失败 |
| 限流隔离 | 打满 API-021 不影响 API-023 至 API-027；运营端打满不影响货主端与移动作业通道（D18）；429 带 `Retry-After` 与 `retryAfterMs` |
| 降级 | 权限、数据域、受理存储分别不可用：一律 503 且不返回 200 空数据；正常成功响应不带 `dataTimestamp`（不得伪造降级标识） |
| 资源边界 | 请求体 16 KB + 1 字节、`limit=101`、`cursor` 超 512 字符、四项必填各超长 1 字符，均在入口 400 拒绝且不产生受理单 |
| 契约一致 | `code/contracts/openapi.json` 与本章的路径、字段名、枚举值、错误码、幂等键、超时预算逐项一致；不一致即构建失败；API-020 的旧参数 `ownerCode` / `name` 不再出现在生成物中 |
