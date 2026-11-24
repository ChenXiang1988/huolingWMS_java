# 04 Open WES 调研报告

> 取证时间 2026-11-24。源码在 `_sources/open-wes`（21 MB，浅克隆）。
> 流程级说明（入库/出库/库存/盘点的事件链路与状态机）见 [04a-open-wes-功能流程.md](./04a-open-wes-功能流程.md)。

## 1. 基本信息

| 项 | 值 | 来源 |
|---|---|---|
| 仓库 | https://github.com/jingsewu/open-wes （Gitee 镜像 `gitee.com/pigTear/open-wes`） | 仓库内 README 徽章 |
| 本地目录 | `_sources/open-wes` | — |
| 默认分支 | master | git |
| 最后提交 | `98361cd` 2026-11-23 `feat(ui): refresh console chrome and regroup WMS sidebar menu` | git log |
| Star / Fork / Watch | 339 / 44 / 6 | GitHub API |
| 主语言 | Java | GitHub API |
| 许可证 | MIT（`LICENSE` 原文 "MIT License / Copyright (c) 2022 jingsewu"） | 实证 |
| 仓库创建 | 2024-12-10 | GitHub API |
| 开放 issue | 7 | GitHub API |

> 备注：首次抓取 GitHub 网页时该仓库返回 404，但 `git clone` 成功、API 返回正常，最后提交为 2026-11-23。以本地克隆与 API 为准。

## 2. 它是什么 / 不是什么

**是**：一个面向自动化仓的 **WES**（仓储执行系统）。核心是"订单 → 波次 → 任务 → 工作站/设备"的执行链路，以容器（container）为单位、以工作站为作业点。

README 原文：

> "Open WES 是一款可定制化的开源仓库执行系统，旨在优化仓储运营流程。该系统能与各类自动化技术无缝集成，提供高效的工作流管理、任务调度和实时数据追踪功能。"

**不是**：

1. 不是纯 WMS。它没有采购/销售/财务，入口是"入库计划单 / 出库计划单"，上游由 WMS/ERP 推送。【实证：`w_inbound_plan_order` / `w_outbound_plan_order` 表与对应 API】
2. 不是设备控制系统。与设备之间是"容器任务"（`ContainerTask`）的委托关系，设备侧由 EMS 执行。【实证：`wes-ems-proxy` 模块 + `e_container_task` 表 + `WmsTaskCallbackFacade`】

## 3. 技术栈

| 项 | 值 | 位置 |
|---|---|---|
| JDK | 17 | `server/build.gradle` `sourceCompatibility = 17` |
| Spring Boot | 3.2.2 | `server/build.gradle` plugin 版本 |
| 构建 | Gradle 多模块（22 个模块） | `server/settings.gradle` |
| 数据库 | MySQL 8.0（`mysql:8.0` 镜像） | `docker-compose.yml` |
| 缓存 | Redis 7.2 | 同上 |
| 注册/配置 | Nacos 2.0.0 | 同上 |
| ORM | Spring Data JPA（`spring-boot-starter-data-jpa` 3.2.2） | `server/build.gradle` |
| AI | Spring AI 1.0.0-M6（BOM） | `server/build.gradle` |
| 前端 | React（`@ant-design/icons`、`ahooks`、`@dnd-kit/*`），工程名 `jupiter` | `client/package.json` |

可部署服务只有 3 个：【实证，`server/server/` 目录】

```
gateway-server    网关
station-server    工作站端
wes-server        业务主服务（67 张表都在这里初始化）
```

## 4. 代码规模

| 指标 | 值 |
|---|---|
| Java 文件 | 1,505 |
| Java 行数 | 76,503 |
| Controller | 67 |
| 建表语句 | 67（`init_create_table.sql` 单文件） |
| 状态枚举 | 20 个（`*StatusEnum.java`） |
| 业务模块 | 22 个 Gradle 模块 |

## 5. 功能结构与代码模块

### 5.1 功能结构（本批唯一有完整菜单数据的项目）

数据来源：`server/server/wes-server/src/main/resources/db/sql/init/user/init_menu.sql`，共 **209 条菜单记录**（`u_menu` 表），字段含 `parent_id` / `type`（1 目录、2 菜单、3 按钮）/ `title` / `path` / `order_num`。下面的树由该 SQL 解析得到，括号内是菜单 `path`。【实证：SQL 数据 + 本地解析】

```
# 智能仓库管理系统
├── 工作站  /wms/workStation
│   ├── 商品收货        /wms/workStation/receiving
│   ├── 盘点            /wms/workStation/count
│   └── 拣货            /wms/workStation/outbound
├── 数据中心  /wms/data-center
│   ├── 入库管理        /wms/data-center/inbound-manage
│   │   ├── 入库单      /wms/data-center/inbound/inbound-plan-order
│   │   ├── 上架任务    /wms/data-center/inbound/putaway-task
│   │   ├── 空箱入库    /wms/data-center/inbound/empty-container-inbound
│   │   └── 收货管理    /wms/data-center/receiving-manage
│   ├── 出库管理        /wms/data-center/outbound-manage
│   │   ├── 出库单      /wms/data-center/outbound/outbound-plan-order
│   │   └── 拣货任务    /wms/data-center/outbound/picking-order
│   └── 库存管理        /wms/data-center/stock-manage
│       ├── 库存查询    /wms/data-center/stock/container-stock
│       ├── 库存调整    /wms/data-center/stock/stock-adjust
│       ├── 库存盘点    /wms/data-center/stock/stocktake
│       ├── 库存异常    /wms/data-center/stock/stock-abnormal
│       └── 库存记录    /wms/data-center/stock/stock-record
├── 配置中心  /wms/config-center
│   ├── 基础信息
│   │   ├── 货主管理            /wms/config-center/basic/owner-management
│   │   ├── 商品管理            /wms/config-center/basic/sku-management
│   │   ├── 批次管理            /mdm/config-center/basic/batch-attribute-management
│   │   ├── 容器规格管理        /wms/config-center/basic/container-spec-management
│   │   ├── 容器管理            /wms/config-center/basic/container-management
│   │   ├── 播种墙管理          /wms/config-center/basic/put-wall-management
│   │   └── 工作站管理          /wms/config-center/basic/work-station
│   ├── 规则管理
│   │   ├── 系统配置            /wms/config-center/rule/system-config
│   │   ├── 枚举字典            /wms/config-center/rule/dictionary-management
│   │   └── 条码解析规则        /wms/config-center/rule/barcode-parse-rule
│   └── 仓库信息
│       ├── 仓库管理            /wms/config-center/warehouse/warehouse-management
│       ├── 仓区管理            /wms/config-center/warehouse/warehouse-area-group
│       ├── 库区管理            /wms/config-center/warehouse/warehouse-area
│       ├── 逻辑区管理          /wms/config-center/warehouse/warehouse-logic
│       └── 库位管理            /wms/config-center/warehouse/location-management
# 接口平台  /api-platform
├── 接口管理  /api-platform/api-management
└── 接口日志  /api-platform/api-log
# 用户中心  /user
├── 角色管理  /user/role-manage
├── 用户管理  /user/user-manage
├── 菜单管理  /user/menu-manage
└── 日志管理  /user/login-log-manage
```

结构上可读出的几点（均有菜单数据支撑）：

1. **三棵根菜单 + 四个 `system_code`**：`parent_id=0` 的根菜单只有 3 条（智能仓库管理系统 / 接口平台 / 用户中心），但 `system_code` 有 4 个取值：`wms` 183 条、`user` 18 条、`mdm` 7 条、`api-platform` 3 条。其中 `mdm`（批次管理等）挂在 WMS 树下，说明"系统归属"与"菜单层级"是两套维度。
2. **作业入口集中在"工作站"**：收货、盘点、拣货三个工作站页面，每个工作站下面挂一排按钮级权限（`type=3`），如"商品收货"下挂"商品收货 / 商品收货满箱 / 商品收货确定 / 商品收货任务详情 / 商品收货异常登记"。**按钮即操作步骤**，这是把现场作业流程直接编码进菜单的典型做法。
3. **配置中心按"基础信息 / 规则 / 仓库"三分**，且仓库下区分了 `仓区（area-group）`、`库区（area）`、`逻辑区（logic）`、`库位（location）` 四层——比多数开源 WMS 多了"逻辑区"。
4. **没有采购、销售、财务、报表菜单**。印证第 2 节的判断：它是执行层，不是完整 WMS。

### 5.2 代码模块

【实证，`server/settings.gradle` 原文】

```
modules-wes:
  wes-common / wes-api / wes-basic / wes-config / wes-inbound / wes-outbound
  wes-printer / wes-stock / wes-task / wes-ems-proxy / wes-algo / wes-stocktake
modules-station:  station / station-api
modules-user:     user / user-api
modules-plugin:   plugin-api / plugin-core / plugin-sdk / plugin-extension
                  script-plugin-api / script-plugin-core / script-plugin-runtime
modules-gateway:  gateway-core
modules-api-platform: api-platform / api-platform-api
modules-search:   search-core / search-api
modules-ai:       ai-core / ai-api / ai-plugin
modules-utils
```

职责可读出的划分逻辑：

- **按作业域**拆：inbound / outbound / stock / stocktake / task / printer
- **按技术关注点**拆：api（对外契约）、basic（主数据）、config、ems-proxy（设备）、algo（分配算法）
- **横切能力**独立：plugin（插件运行时）、api-platform（对外 API 平台）、search、ai、user

包内是典型的 DDD 四层：【实证，以 `wes-inbound` 为例】

```
org.openwes.wes.inbound
├── controller/                  REST 入口
├── application/                 应用服务 + 事件订阅（*Subscriber）
└── infrastructure/
    ├── persistence/po/          持久化对象（*PO）
    ├── persistence/mapper/
    ├── persistence/transfer/     PO <-> 领域对象转换
    └── repository/impl/
```

## 6. 数据模型（67 张表，按前缀分组）

【实证，`server/server/wes-server/src/main/resources/db/sql/init/wes/init_create_table.sql`】

**主数据 `m_`（7）**

```
m_sku_main_data            SKU 主数据
m_owner_main_data         货主
m_warehouse_main_data     仓库
m_batch_attribute_config  批次属性配置
m_barcode_parse_rule      条码解析规则
m_dictionary / m_system_config
m_address
```

**仓库与库位 `w_`（库位相关）**

```
w_warehouse_area          库区
w_warehouse_area_group    库区组
w_aisle                   巷道
w_location                库位
w_warehouse_logic         仓库逻辑（配置）
w_warehouse_config
```

**容器 `w_`**

```
w_container               容器
w_container_spec          容器规格
w_container_stock         容器库存（按容器存）
w_container_stock_transaction  容器库存流水
w_transfer_container / w_transfer_container_record  容器搬运
```

**入库 `w_`**

```
w_inbound_plan_order / _detail       入库计划单 头/行
w_accept_order / _detail             收货单 头/行
w_put_away_task / _detail            上架任务 头/行
w_empty_container_inbound_order / _detail  空容器入库
```

**出库 `w_`**

```
w_outbound_plan_order / _detail      出库计划单 头/行
w_outbound_wave                      波次
w_outbound_pre_allocated_record      预分配记录
w_picking_order / _detail            拣货单 头/行
w_put_wall / w_put_wall_slot         分播墙 / 格口
```

**库存 `w_`**

```
w_sku_batch_stock            SKU+批次库存
w_stock_adjustment_order / _detail  库存调整单
w_stock_abnormal_record      异常库存记录
```

**盘点 `w_`**

```
w_stocktake_order / _detail  盘点单
w_stocktake_task / _detail   盘点任务
w_stocktake_record           盘点记录
```

**任务与设备 `w_` / `e_`**

```
w_operation_task                    作业任务（核心任务表）
e_container_task                    容器任务（发给设备侧）
e_container_task_and_business_task_relation  设备任务与业务任务关联
e_ems_location_config               设备库位映射
```

**其他**

```
d_domain_event        领域事件表（事件驱动落地）
p_print_template / p_print_rule / p_print_config / p_print_record   打印
a_api / a_api_config / a_api_log                                     开放 API 平台
u_user / u_role / u_menu / u_role_menu / u_user_role / u_login_log   权限
w_work_station / w_work_station_config                               工作站
w_sku_batch_attribute                                                批次属性值
```

这张表清单本身的价值很高：它把"WES 该有哪些对象"讲清楚了，而且**容器库存（`w_container_stock`）与 SKU 批次库存（`w_sku_batch_stock`）是两套账**，这是容器化仓库（货到人/料箱）与普通仓库最大的模型差异。

## 7. 核心机制

### 7.1 单据状态机（20 个枚举，逐条实证）

| 对象 | 状态 |
|---|---|
| 入库计划单 | `NEW` 新单据 → `ACCEPTING` 收货中 → `ACCEPTED` 收货完成；`CANCEL` 取消、`CLOSED` 关闭 |
| 出库计划单 | `NEW` → `SHORT_WAITING` 缺货等待 → `ASSIGNED` 分配完成(库区) → `DISPATCHED` 派单完成 → `PICKING` 拣货中 → `PICKED` 拣货完成；`CANCELED` 已取消 |
| 拣货单 | `NEW` → `DISPATCHED` → `PICKING` → `HANGING` 挂起 → `PICKED`；`CANCELED` |
| 波次 | `NEW` → `PROCESSING` → `DONE`；`CANCELED` |
| 作业任务 | `NEW` → `PROCESSING` → `PROCESSED`；`CANCELED` |
| 容器任务 | `ContainerTaskStatusEnum`（未逐个读值） |

【实证：`wes-api` 下各 `*StatusEnum.java` 的枚举常量与中文 label 原文】

**设计要点：出库状态里 `ASSIGNED` 的注释是"分配完成(库区)"**，即分配粒度到库区而非具体库位，具体库位由后续任务决定。这与 OpenWMS 的 `targetLocationGroup` 思路一致，但 OpenWES 更进一步——把它做成了状态机的一个节点。

### 7.2 任务模型

`OperationTask` 字段（【实证】`wes-task/.../domain/entity/OperationTask.java`）：

```
warehouseCode, taskType(OperationTaskTypeEnum), workStationId, priority,
skuId, skuBatchStockId, skuBatchAttributeId, containerStockId,
sourceContainerCode, sourceContainerFace, sourceContainerSlot, boxNo,
requiredQty, operatedQty, abnormalQty,
targetLocationCode, targetContainerCode, targetContainerSlotCode,
transferContainerRecordId
```

读出来的信息：

1. 任务绑定**工作站**（`workStationId`），不是绑定人 → 作业模式是"人到站/货到站"。
2. 任务同时持有序存引用（`skuBatchStockId`、`containerStockId`）与容器定位（面 `face`、格 `slot`）→ 料箱机器人的典型寻址。
3. 有 `requiredQty / operatedQty / abnormalQty` 三段数量 → 正常量、实操作量、异常量分开记。

### 7.3 分配算法可插拔（W-12 已解：远程算法只有空文件）

`wes-algo` 只有 10 个类，但结构清楚：【实证】

```
StockAllocationService                       库存分配接口
├── ManualAreaStockAllocationServiceImpl     人工区分配
└── RobotAreaStockAllocationServiceImpl      机器人区分配
RobotAreaOrderAssignService                  机器人区订单分配
ManualAreaPickingOrderAlgoApiImpl / RobotAreaPickingOrderAlgoImpl   拣货单算法
RemoteAlgorithmService                       远程算法服务
```

「人工区 / 机器人区」两套实现，说明算法是按仓型分策略。

但 `RemoteAlgorithmService.java` **是 0 字节空文件**（`wc -l` 返回 0，`cat` 无输出）。所以"可外接远程算法"只是预留了文件位置，**没有实现**。

### 7.4 插件体系（19 个扩展点）

`modules-plugin/plugin-extension` 下 19 个类，按业务域给出接口，例如：【实证】

```
IOutboundWavePlugin            波次插件
IOutboundWaveSplitAction       波次拆分动作
IOutboundWaveWaveAction        波次执行动作
IOutboundWaveLifecycleListener 波次生命周期监听
```

**脚本引擎是 Groovy**（W-11 已解）：`modules-utils/script-engine/build.gradle` 依赖 `org.apache.groovy:groovy` + `caffeine`（脚本缓存）。`script-plugin-runtime` 只是引用它，不自带引擎。

### 7.5 AI 模块是开发工具，不是业务 AI（W-14 已解）

【实证】三个 Controller 的端点：

```
AiChatController     GET  /chat                  LLM 对话
                     POST /analysis              SQL 分析
                     POST /generateJsFunction    生成 JS 函数（Flux 流式返回）
                     POST /generateCode          生成代码（Flux 流式返回）
AiDraftController    POST /draft/stream          草稿流式生成（SSE）
                     GET  /generations/{pluginKey}  生成历史
                     POST /corpus/refresh        刷新语料
DocumentController   （文档相关）
```

结论：AI 模块的用途是**生成代码、生成 JS 函数、分析 SQL、生成草稿**，属于"AI 辅助开发/插件生成"，**不是仓储调度类 AI**。README 说的"AI 驱动的优化"在代码里没有对应实现 →【未核实，代码中未找到】。

### 7.5 事件驱动

表 `d_domain_event` + 各模块的 `*Subscriber`（如 `InboundOrderSubscriber`、`PutAwayTaskSubscriber`、`StockEventSubscriber`、`OperationTaskSubscriber`）说明模块之间通过领域事件解耦。【实证：类名 + 表名；事件总线实现未读】

## 8. 接口与集成

- REST：67 个 Controller。【实证】
- 开放 API 平台：`a_api` / `a_api_config` / `a_api_log` 三张表 + `modules-api-platform`。【实证】
- 设备侧：`wes-ems-proxy` → `ContainerTask` 下发，`WmsTaskCallbackFacade` 接收回调。【实证：类名】
- 打印：`wes-printer` + 4 张 `p_*` 表（模板/规则/配置/记录）。【实证】
- WebSocket：`spring-boot-starter-websocket` 依赖存在。【实证：build.gradle】
- MQ：compose 中有 `kafka-exporter`，但 **compose 未定义 kafka 服务**；代码中是否有 Kafka 客户端未逐项确认 →【未核实】。

## 9. 部署方式（本批项目里最完整的）

`docker-compose.yml` 定义的服务：【实证】

```
frontend, gateway-server, station-server, wes-server,
mysql:8.0, redis:7.2, nacos/nacos-server:2.0.0,
prometheus, grafana, loki, promtail, alertmanager,
node-exporter, mysqld-exporter, redis_exporter, kafka-exporter
```

- 一键启动：`HOST_IP=$(hostname -I | awk '{print $1}') docker-compose up -d`。【自述 README，未执行】
- 生产用 `docker-compose.prod.yml`。【实证：文件存在】
- 自带可观测性栈（Prometheus + Grafana + Loki）。【实证】
- 数据库初始化：`initdb.d/create_databases.sql` + `init_create_table.sql` + 菜单/字典/API 初始化脚本。【实证】

## 10. 活跃度

- 最后提交 2026-11-23（抓取当天仍在提交），`pushed_at` 2026-11-23。
- 仓库创建 2024-12-10，约两年积累 1,505 个 Java 文件。
- 开放 issue 7 个。【实证 + API】

## 11. 对我们可参考 / 不建议参考

**建议参考（价值最高的一份）**

1. **表清单直接当"WES 对象字典"用**：67 张表的分组（主数据 / 库位 / 容器 / 入库 / 出库 / 库存 / 盘点 / 任务设备）就是一张完整的领域地图。见 `init_create_table.sql`。
2. **容器库存与 SKU 批次库存两套账**：`w_container_stock` + `w_sku_batch_stock`。做料箱/货到人必须先有这个双账模型。
3. **出库状态机**：`NEW → SHORT_WAITING → ASSIGNED → DISPATCHED → PICKING → PICKED`，把我们常写的"已分配/已拣货"细化到了"分配完成(库区)"和"派单完成"两步。
4. **任务与设备任务分离**：`w_operation_task`（业务任务）与 `e_container_task`（设备任务）+ 关联表。这是 WES 与 WCS/EMS 解耦的关键。
5. **算法按仓型分策略 + 远程算法出口**：`wes-algo` 的 ManualArea / RobotArea 双实现。
6. **插件扩展点清单**：19 个接口，可直接对照我们的扩展需求。

**不建议参考**

1. **代码成熟度**：提交信息里有大量 UI 调整与重构，`docs/` 下是开发计划文档（`optimization-plan.md`、`plans/`），说明仍在快速演进，不宜直接作为生产基线。【实证：docs 目录内容】
2. **模块爆炸**：22 个 Gradle 模块对单人/小团队维护成本偏高。
3. **`wes-api` 与 `wes-*` 的双份 DTO**：每个域都有 `api/dto` 与 `domain/entity` 两套对象 + `*Transfer` 转换类，样板代码量大。

## 12. 未核实清单

1. 项目能否 `docker-compose up` 启动成功 —— 未执行（需要拉取 MySQL/Nacos 等镜像）。
2. 事件总线用什么中间件（RabbitMQ / Kafka / Spring Event）—— 只看到 `d_domain_event` 表与 Subscriber 类。
3. `script-plugin-runtime` 用哪种脚本引擎。
4. AI 模块（`AiChat`、`AiDraft`）的实际用途与可用性。
5. `RemoteAlgorithmService` 的协议（HTTP/Feign）。
6. 设备侧 EMS 的真实接口格式 —— `ContainerTask` 字段未逐个读。
