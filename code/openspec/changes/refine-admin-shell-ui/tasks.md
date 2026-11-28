# Tasks

依据：`proposal.md`、`specs/admin-shell-visual/spec.md`、本变更 `design.md`「前端设计」章，以及 `v2/12a` 的 `UI-P12`~`UI-P16`。令牌与圆角阴影一律不动。

> 第二轮按原型框架调整：品牌移顶栏、取消面包屑与顶栏折叠按钮、侧栏改两级模块树、新增模块页签。第一轮已完成的组件与页面工作保留，下表为最终交付态。

## 一、前端

### 1 组件底座

- [x] 1.1 在 `code/web/packages/design/package.json` 与 `apps/admin/package.json` 增加 `@element-plus/icons-vue` 依赖，版本 `^2.3.1`，并在 `index.ts` 导出新组件。
- [x] 1.2 新增 `code/web/packages/design/src/PageHeader.vue`：属性 `title`、`description`、`showDate`；插槽 `actions`。标题 20px/600、描述 13px 辅助色、页头下间距 16，右侧操作右对齐。
- [x] 1.3 新增 `code/web/packages/design/src/ModuleTabs.vue`：属性 `tabs`（`{path,title}[]`）与 `active`；横排、当前项下划线、`tabs.length <= 1` 时不渲染。
- [x] 1.4 扩展 `AppShell`：新增 `nav`（模块树，每项含 `title`、`icon`、`children`）与 `brand`（平台名与副标题）；保留 `menus` 作为兼容入参，`nav` 优先；删除 `breadcrumbs` 入参与面包屑渲染。

### 2 外壳

- [x] 2.1 顶栏按 `UI-P12` 改：左侧品牌区（32×32 方块标记 + 「货灵」+ 副标题），右侧帮助图标与用户区；去掉面包屑与折叠按钮。帮助图标打开使用说明抽屉，内容是已实现的快捷键与字段说明，不放无动作的空图标。
- [x] 2.2 侧栏按 `UI-P13` 改：去掉品牌区；一级模块（图标 16 + 名称 + 展开箭头）加二级页面（缩进 16、无图标、高 36）；同一时刻只展开当前页所在模块；当前项用 `UI-T21` 选中底。
- [x] 2.3 侧栏底部工具条：帮助与折叠按钮；收起态只留图标。收起态只显示模块图标，点击进入该模块首个有权限页面，悬浮 300ms 显示模块名。
- [x] 2.4 折叠初始值：有 `localStorage` 取值用存储值，无值按 `window.innerWidth < 1400` 收起，读取异常同样按视口判断。1366×768 下不得出现横向滚动条。

### 3 运营端页面

- [x] 3.1 `route-meta.d.ts` 只保留 `group` 一个可选字段，删除 `icon` 与 `breadcrumb`；`router/index.ts` 按模块表补齐 `group`；`router/navigation.ts` 用固定表提供模块图标，并据当前路由推导模块树、当前模块与页签集合。
- [x] 3.2 `AdminLayout.vue` 组装 `brand`、`nav`、`collapsible`、`user` 与 `ModuleTabs`；菜单仍取「路由表 ∩ 权限集」，`canVisit` 判定不变，无权限页在侧栏与页签都不渲染。
- [x] 3.3 `LoginView.vue` 左右分栏：左栏端名、定位句「多货主同仓作业，一屏看清库存与单据」与三条能力说明；右栏表单卡宽 400、内边距 32；窄于 1280 隐藏左栏；失败时账号保留、密码清空、焦点回密码框。
- [x] 3.4 `HomeView.vue` 页头加三卡：当前账号、数据域（按数量）、功能入口；只读登录态，不新增请求；无数据时给说明文字。
- [x] 3.5 六个列表页（`AccountsView`、`RolesView`、`AuditView`、`ArchivesView`、`ArchiveReviewView`、`ArchiveChangeRequestsView`）外层统一为 `PageHeader` 加容器，筛选区与表格同容器、分页在右下。列、筛选项、排序、权限判断、提交逻辑不动。
- [x] 3.6 `ForbiddenView.vue` 外层统一为新容器，文案与跳转不变。

### 4 自检

- [x] 4.1 `cd code/web && pnpm typecheck` 通过。
- [x] 4.2 `cd code/web && pnpm build` 通过。
- [x] 4.3 手工验收：登录页、工作台、六个列表页在 1366×768 与 1920×1080 下无横向滚动与重叠；侧栏二级展开收起正常；模块页签与侧栏高亮联动；工作台不显示页签。
- [x] 4.4 兼容验收：`apps/owner`、`apps/site` 不传 `nav` 时按旧 `menus` 平铺渲染，尺寸与改造前一致。

## 二、后端

本次无后端任务。`design.md`「后端设计」章已说明：不改数据模型、不改鉴权、不改接口、无迁移脚本。

## 三、前后端联调

本次无联调任务。本次不新增、不修改接口，列表页仍调用既有接口；`design.md`「API 接口契约」章「契约验收」一节给出四项无需新增接口即可执行的核对。
