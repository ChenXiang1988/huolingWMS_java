# 运营端外壳与页面骨架 · 实施验证记录

> 变更名：`refine-admin-shell-ui`。本文件逐项记录实测命令、结果与截图证据。命令由主代理执行并复核，不采信子代理自述。

## 1 交付形态与本次改动

令牌与视觉取值未动：`packages/design/src/tokens.css` 零改动，主色仍 `#2E2E2C`，圆角仍为控件 4、卡片 8，阴影仍只用 Element Plus 三档。

第一轮补齐顶栏、侧栏分组、登录页、工作台与列表页外层；第二轮按原型框架改为品牌顶栏、两级模块侧栏与模块页签；第三轮按 `12` 号规范修正表格行高与详情抽屉宽度。

| 文件 | 改动 | 承接 |
|---|---|---|
| `packages/design/src/PageHeader.vue`（新增） | 标题、描述、日期、右侧操作插槽 | `web-impl` |
| `packages/design/src/ModuleTabs.vue`（新增） | 模块内页面页签，单页不渲染，点击抛 `navigate` | `web-impl` |
| `packages/design/src/AppShell.vue` | 顶栏品牌区加帮助与用户下拉（去面包屑、去折叠按钮）；侧栏两级模块树、只展开当前模块、底部工具条、收起态模块图标带选中底；`nav`／`brand` 入参，保留 `menus` 兼容 | `web-impl` |
| `packages/design/src/DataTable.vue` | 数据行高 40、紧凑档 32、表头 44，按 `UI-C01` | `web-impl` |
| `packages/design/src/DetailDrawer.vue` | 未显式传宽度时按 880（带导航档），按 `UI-C04` | `web-impl` |
| `packages/design/src/FilterBar.vue` | 筛选区子项按内容宽排布，修复 `FormInput` 撑满导致换行 | `web-impl` |
| `packages/design/src/{FormInput.vue,format.ts,index.ts,package.json}` | 焦点句柄、日期格式化、导出、图标依赖 | `web-impl` |
| `apps/admin/src/router/{index.ts,route-meta.d.ts,navigation.ts}` | 只保留 `group`；模块图标集中固定表；模块树与页签集合推导 | `web-impl` |
| `apps/admin/src/layouts/AdminLayout.vue` | 组装品牌、模块树、页签与用户区；菜单仍取路由表与权限集交集 | `web-impl` |
| `apps/admin/src/views/LoginView.vue` | 左右分栏、定位句、失败态处理 | `web-impl` |
| `apps/admin/src/views/HomeView.vue` | 页头加三卡，数据域按数量展示，入口图标取模块图标 | `web-impl` |
| `apps/admin/src/views/AccountsView.vue` | 外层页头与容器；详情抽屉补 `:columns="2"` | `web-impl` |
| 五个列表页与 `ForbiddenView.vue` | 外套 `PageHeader` 与卡片容器，列与交互零改动 | `web-impl` |
| `humandesign/sysdesign/v2/12a`、`v2/log.md` | 新增 `UI-P12`~`UI-P16`，三轮口径调整并留痕 | 主代理（文档） |

`apps/owner`、`apps/site` 零改动。

## 2 实测命令与结果

| # | 命令 | 结果 |
|---|---|---|
| 1 | `cd code/web && pnpm install --frozen-lockfile` | 通过，`Already up to date` |
| 2 | `cd code/web && pnpm --filter @wms/design typecheck` | 通过，本次改动的设计包无类型错误 |
| 3 | `cd code/web && pnpm typecheck`（全量） | 通过，六个包全部 Done |
| 4 | `cd code/web && pnpm build`（全量） | 通过，三端全部 Done，仅既有 chunk 体积提示 |
| 5 | `python3 .codebuddy/tools/check-design.py refine-admin-shell-ui` | `ERROR 0 WARN 1 INFO 7` |
| 6 | `python3 .codebuddy/tools/check-design.py --only norms` | `ERROR 0 WARN 0` |
| 7 | `python3 .codebuddy/tools/check-agent-dispatch.py` | `ERROR 0 WARN 0` |

## 3 类型检查的阶段性阻断（已恢复）

实施中途，全量 `pnpm typecheck` 曾报两处错误，均来自并行推进的另一变更 `add-owner-archive`，本次未触碰其文件：

| 位置 | 错误 |
|---|---|
| `code/web/packages/request/src/index.ts` | `TS2308`：`./archive` 与 `./owner-archive` 都导出 `OwnerArchiveQuery` |
| `code/web/apps/admin/src/views/ArchivesView.vue` | `TS2353`：`ownerCode` 不在 `OwnerOptionQuery` 类型里 |

按决定未介入该变更。2026-11-26 复查时该变更已修复，全量 `typecheck` 与 `build` 均已恢复通过，见第 2 节第 3、4 行。

中途另有一次全量 `build` 在 `apps/owner` 瞬时失败，单独构建该应用与随后复跑全量均通过，判定为并行构建的瞬时冲突，未复现。

## 4 手工验收

用无头浏览器登录本机运行中的运营端（5173）与 Hub（8094）实测，账号 `admin`。

| 项 | 实测值 / 结果 |
|---|---|
| 表格数据行高（`UI-C01` 要求 40） | 40 |
| 表头高（`UI-C01` 要求 44） | 44 |
| 详情抽屉宽度（`UI-C04` 带导航 880） | 880 |
| 账号详情字段列数 | 两列，每行两对标签与值 |
| 顶栏 | 左侧「货」方块、货灵、WMS 仓储管理平台；右侧帮助图标与用户胶囊；无面包屑、无折叠按钮 |
| 侧栏展开 | 概览、主数据、权限与审计三个模块；当前页所在模块展开，二级三项，当前页高亮 |
| 侧栏收起（1366×768） | 宽 64，只显模块图标，当前模块带选中底；`scrollWidth` 与 `clientWidth` 均为 1366，无横向滚动 |
| 模块页签 | 商品档案页显示「档案审核 / 商品档案 / 变更申请审核」，当前项下划线；工作台属单页模块，不显示页签 |
| 帮助抽屉 | 含导航说明三条与账号、商品档案两组状态枚举释义，内容来自字典包，无占位文案 |
| 默认展开与收起 | 视口 ≥1400 初始展开，<1400 初始收起；手动切换后写入 `localStorage` 保留 |
| 兼容 | `apps/owner`、`apps/site` 未传 `nav`，按旧 `menus` 平铺渲染 |

## 5 过程中修掉的五处缺陷

均由截图或实测发现，类型检查查不出：

1. **筛选区换行**：`FormInput` 根容器写死 `width: 100%`，在 `FilterBar` 内吃满整行，把第二个控件挤到下一行。改在 `FilterBar` 约束直接子项宽度，不改 `FormInput` 的表单语义。
2. **工作台顶栏左侧空荡**：第一轮靠面包屑填充，第二轮改为顶栏品牌区，面包屑整体移除。
3. **收起态看不出所在模块**：补当前模块图标选中底与图标文字加深，只用 `UI-T21` 令牌。
4. **表格行高与表头高不符规范**：实测 49 / 40，改为 40 / 44。
5. **详情抽屉宽度不符规范**：默认 720 而导航恒常驻，改为 880；账号详情补两列，避免单列摊满 880 宽。

## 6 遗留 WARN 与理由

| 项 | 理由 |
|---|---|
| `FE-07`（无页面接口映射表） | 本次无接口新增或变更，写映射表会引入不存在的接口编号；页面与接口关系维持现状 |

## 7 风险与边界

- **视觉力度受令牌约束**：主色暖灰近黑、圆角 8、阴影只用 Element 三档，是明确取舍。
- **详情字段仍显松**：值右对齐到列宽是既定标准（2026-11-19 定稿），880 宽下标签与值之间留白较大，已确认保持现状。
- **收起态区分较弱**：选中底对比度约 1.14:1，靠图标文字加深补充；要更明显需改 `UI-T21`。
- **1400 阈值 1px 边界**：视口恰为 1400 且手动展开时总宽 1401，多 1px 横向滚动，1366 与 1280 不受影响。
- **全量门禁已闭合**：`add-owner-archive` 修复后复查，`typecheck`、`build`、后端 `mvn clean verify` 与两道校验脚本全部通过。

## 8 结论

界面相关的实测项全部通过：表格 40/44、抽屉 880、1366 无横向滚动、模块页签与侧栏联动、帮助抽屉内容真实、兼容端不受影响。

四项全量门禁已闭合：后端 `mvn clean verify` 退出码 0，前端 `pnpm typecheck` 与 `pnpm build` 三端全过，`check-design` 与 `check-agent-dispatch` 均 0 错误。可归档。
