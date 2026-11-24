# 05 OpenBoxes 调研报告

> 取证时间 2026-11-24。源码在 `_sources/openboxes`（84 MB，浅克隆，分支 develop）。

## 1. 基本信息

| 项 | 值 | 来源 |
|---|---|---|
| 仓库 | https://github.com/openboxes/openboxes | — |
| 本地目录 | `_sources/openboxes` | — |
| 默认分支 | **develop** | git |
| 最后提交 | `0b762c5` 2026-11-23（与 WMS 收货单完成状态相关） | git log |
| Star / Fork / Watch | 937 / 518 / 29 | GitHub API |
| 主语言 | **Groovy** | GitHub API |
| 许可证 | **EPL-1.0**（`LICENSE.md` 原文 "Eclipse Public License -v 1.0"） | 实证 |
| 仓库创建 | 2012-09-28 | GitHub API |
| 开放 issue | **220** | GitHub API |

> Fork/Star 比 0.55，是本次调研中分叉率最高的项目。

## 2. 它是什么 / 不是什么

**是**：面向医疗机构的仓库与库存管理系统，起源是 2010 年海地地震后的医疗物资配送数字化。README 原文：

> "OpenBoxes was initially conceptualized by staff at Partners In Health ... to digitize the delivery, inventory, and consumption of emergency medical supplies in Haiti"

**不是**：

1. 不是 Java 项目。语言是 Groovy，框架是 Grails（JVM 上的全栈框架）。**如果只接受 Java 源码，这个项目不能作为代码参考**，但它的数据模型仍可读。
2. 不是通用制造业/电商 WMS。核心业务词是 requisition（领用申请）、shipment（调拨发运）、cycle count（循环盘点）、donation（捐赠），不是采购销售。

## 3. 技术栈

| 项 | 值 | 位置 |
|---|---|---|
| 语言 | Groovy | `grails-app/` 全目录 |
| 框架 | Grails **3.3.16** | `gradle.properties` `grailsVersion=3.3.16` |
| ORM | GORM / Hibernate 5.2 | `gradle.properties` 注释与 `build.gradle` |
| 构建 | Gradle（`gradlew`） | 根目录 |
| 前端 | Node + Webpack + React（`preset-react`、`react-styleguidist`） | `package.json`、`styleguide.config.js` |
| DB 版本管理 | **Liquibase**（228 个 changelog 文件） | `liquibase/` |
| 容器 | Dockerfile + 3 个 compose（base / hostdb / remotedb） | `docker/` |
| CI | GitHub Actions（on-change / dbdocs / docker-image） | `.github/workflows` |
| JDK | `sourceCompatibility = 1.8` | `build.gradle` |

## 4. 代码规模

| 指标 | 值 |
|---|---|
| Groovy 文件 | 1,348 |
| Java 文件 | **0** |
| 领域类（`grails-app/domain`） | 119 |
| Controller（`grails-app/controllers`） | 未统计数量（按 Grails 约定每个域一个） |
| Liquibase changelog | 228 |
| SQL 建表语句（仓库内 .sql） | 10（主要靠 Liquibase XML 演进，不是 SQL） |

## 5. 功能结构与代码模块

### 5.1 功能结构（按 Controller 域 + View 目录双向实证）

Grails 的约定是"一个 Controller 对应一组页面（View）"。下面按 Controller 域整理功能，每项后面的类名是代码入口。【实证：`grails-app/controllers/org/pih/warehouse/*`，页面存在性用 `grails-app/views/*` 交叉验证】

```
产品管理 product/
  Product / ProductGroup / ProductCatalog / Category / Attribute
  ProductSupplier / ProductPackage / ProductComponent / ProductType / ProductsConfiguration

库存 inventory/
  InventoryBrowser          库存浏览器
  Inventory / InventoryItem / InventoryLevel / InventorySnapshot
  StockMovement             库存移动
  StockTransfer             库存调拨
  TransactionEntry / TransactionType   事务录入与类型
  Replenishment             补货

收货 receiving/ + putaway/
  Receipt                   收货单
  PartialReceiving          部分收货
  PutAway                   上架

发运 shipping/
  Shipment / ShipmentItem / ShipmentType / Shipper / ContainerType
  ShipmentWorkflow / CreateShipmentWorkflow   发运工作流
  ReferenceNumber / ShipmentEvent

拣货 picklist/ + outbound/
  Picklist / PicklistItem   拣货单
  PackingList               装箱单

订单 order/
  PurchaseOrder             采购订单
  Order / OrderAdjustmentType
  ReceiveOrderWorkflow      订单收货工作流

领用 requisition/
  Requisition / RequisitionItem / RequisitionTemplate
  DeliveryNote              送货单

盘点（页面 + 服务）
  cycleCount 页面目录、CycleCount/CycleCountItem/CycleCountRequest 领域类
  InventoryAuditReport / InventoryAudit 系列（审计）

报表 reporting/
  Consumption               消耗
  GoodsReceiptNote          入库单报表
  IndicatorApi / Report / InventoryAuditReport

其他
  invoice/Invoice           发票
  stocklist/Stocklist + StocklistManagement   库存清单
  batch/Batch               批次
  allocation/AllocationRequest  分配请求
  api/                      约 40 个 *ApiController（对外 REST）
  admin/                    后台管理
  user/                     用户
```

页面目录可交叉验证的功能（【实证】`grails-app/views/` 下的目录名）：

```
dashboard, cycleCount, createShipmentWorkflow, deliveryNote, dataExport,
consumption, container, category, budgetCode, batch, attribute, auth, api, admin
```

三点结构特征：

1. **"流程"被单独建模**：`ShipmentWorkflow`、`CreateShipmentWorkflow`、`ReceiveOrderWorkflow` 是独立 Controller/领域对象，不是 Service 里的 if-else。这是 Grails 社区常见的 workflow 建模方式。
2. **库存查询（Browser）与库存事务（Transaction）分开**：`InventoryBrowser` 只读展示，`TransactionEntry` 负责记账。
3. **API 与页面双轨**：`api/` 下约 40 个 `*ApiController`，页面端各自还有 Controller，说明同一功能有页面入口和 REST 入口两套。

### 5.2 代码模块

`grails-app` 是标准 Grails 结构：【实证】

```
grails-app/
├── controllers/     REST/页面入口
├── domain/          119 个领域类（按业务域分包）
├── services/        服务层
├── jobs/            定时任务
├── migrations/      数据迁移
├── views/           GSP 页面
└── conf/
```

领域包分组（这是最有价值的部分）：【实证】

```
domain/org/pih/warehouse/
├── core/          Location, LocationType, LocationGroup, Party, Organization,
│                  Supplier, Document, Event, EventLog, UnitOfMeasure, User, Role
├── product/       Product, ProductGroup, ProductCatalog, ProductSupplier,
│                  ProductPackage, ProductComponent, Attribute, Category
├── inventory/     Inventory, InventoryItem, InventoryLevel, InventorySnapshot,
│                  Transaction, TransactionEntry, TransactionType,
│                  InboundStockMovement, OutboundStockMovement,
│                  CycleCount + CycleCountItem/Summary/Details/Request/Candidate
├── receiving/     Receipt, ReceiptItem
├── shipping/      Shipment, ShipmentItem, ShipmentType, ShipmentMethod,
│                  Shipper, Container, ContainerType
├── picklist/      Picklist, PicklistItem
├── order/         Order, OrderItem, OrderAdjustment, OrderType
└── requisition/   Requisition, RequisitionItem
```

## 6. 数据模型

### 6.1 InventoryItem（批次与效期）

字段（【实证】`grails-app/domain/org/pih/warehouse/inventory/InventoryItem.groovy`）：

```groovy
String  lotNumber                        // 批号
Date    expirationDate                   // 效期
Integer quantity
Integer quantityOnHand                   // 在手量
Integer quantityAvailableToPromise       // 可承诺量
Boolean disableRefresh
// 方法：isRecalled() / isPickable() / getIsDefault()
String toString() { "${product?.productCode}:${lotNumber}:${expirationDate}" }
```

三点可读出的设计：

1. **同一商品按 批号 + 效期 拆成不同 InventoryItem**（toString 由三元组构成）。
2. **在手量与可承诺量分两个字段**，且可承诺量是独立存储而非实时计算。
3. **召回状态与可拣状态是方法**（`isRecalled()`、`isPickable()`），不是字段。

### 6.2 Transaction / TransactionEntry（库存事务账）

【实证】`Transaction.groovy`：

```groovy
Date   transactionDate      // Date entered into the warehouse
String transactionNumber
Date   dateConfirmed
static hasMany   = [transactionEntries: TransactionEntry]
static belongsTo = [LocalTransfer, Requisition, Shipment]
```

库存变更不是直接 UPDATE 数量，而是通过 Transaction（事务头）+ TransactionEntry（事务行）记录，事务可挂到调拨（LocalTransfer）、领用（Requisition）、发运（Shipment）上。**这是"库存=流水累计"的经典建模**。

### 6.3 库存层级

`Inventory` / `InventoryItem` / `InventoryLevel` / `InventorySnapshot` 四层并存：【实证：类存在；层级关系需读 GORM 关联，本次未逐行读 → 部分【未核实】】

## 7. 核心机制

1. **循环盘点（Cycle Count）建模最完整**：`CycleCount`、`CycleCountItem`、`CycleCountRequest`、`CycleCountCandidate`、`CycleCountSummary`、`CycleCountDetails`、`PendingCycleCountRequest` 七个类，把"盘点请求 → 候选 → 执行 → 差异汇总"分开建模。【实证：类清单】
2. **领用制而非销售制**：`Requisition` / `RequisitionItem` 是医疗场景特有的"内部领用"，出库不是卖，是发放。【实证：类存在】
3. **数据库演进用 Liquibase**：228 个 changelog XML，说明表结构是增量演进而非一次性 DDL。【实证：文件数】
4. **多语言**：`crowdin.yml` + `i18n/` 目录，走 Crowdin 平台。【实证】

## 8. 接口与集成

- Web 页面（GSP）+ REST controller。具体 API 清单未整理 →【未核实】
- 无消息队列、无设备对接痕迹（医疗场景不需要）。【实证：依赖清单无 MQ】
- 数据库文档：`dbdocs.yml` workflow + `dbdocs` 目录，自动生成 DB 文档。【实证】

## 9. 部署方式

```
docker/docker-compose-base.yml     基础环境
docker/docker-compose-hostdb.yml   本地数据库
docker/docker-compose-remotedb.yml 远程数据库
docker/Dockerfile
```

- 依赖 MySQL（Liquibase 驱动）。【实证】
- 需要 JDK 8 + Grails 3.3.16（该版本已停止维护）。【实证：`gradle.properties`】

## 10. 活跃度

- 最后提交 2026-11-23（抓取前一天），`pushed_at` 2026-11-23。**仍在活跃开发**。
- 开放 issue 220 个，说明积压较多。【GitHub API】
- 仓库已运行 14 年（2012 创建）。【GitHub API】

## 11. 对我们可参考 / 不建议参考

**建议参考（模型层面）**

1. **批次=商品+批号+效期** 的建模与 `toString` 三元组。见 `InventoryItem`。
2. **在手量 / 可承诺量分离**：`quantityOnHand` 与 `quantityAvailableToPromise` 是两个字段。我们的可用库存若只在查询时算，可以对照这套做法。
3. **事务账驱动库存**：`Transaction` + `TransactionEntry`，库存变动必有事务号与来源单据（`belongsTo [LocalTransfer, Requisition, Shipment]`）。这是可审计库存的标准形状。
4. **循环盘点的七类拆分**：盘点请求与盘点执行分开，候选与结果分开。

**不建议参考**

1. **语言与框架**：Groovy + Grails 3.3.16 + JDK 8，技术栈已过时，不能作为 Java 项目模板。
2. **业务域**：领用/捐赠/召回是医疗专属，通用仓储用不上。
3. **许可证**：EPL-1.0 是弱 copyleft，修改后分发有源码开放义务，商用前需法务确认。

## 12. 未核实清单

1. `Inventory` / `InventoryItem` / `InventoryLevel` / `InventorySnapshot` 的具体关联与生成规则 —— 未读 GORM mapping。
2. 库存数量的并发更新方式 —— 未读 Service。
3. REST API 清单与鉴权方式 —— 未整理。
4. 220 个开放 issue 的性质（bug / 需求）—— 未逐条看。
5. 能否在 JDK 17/21 下运行 —— 构建文件写的是 1.8，未验证兼容性。
