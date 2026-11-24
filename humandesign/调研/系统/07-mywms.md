# 07 myWMS 调研报告

> 取证时间 2026-11-24。源码在 `_sources/mywms`（118 MB，浅克隆）。

## 1. 基本信息

| 项 | 值 | 来源 |
|---|---|---|
| 仓库 | https://github.com/wms2/mywms | — |
| 本地目录 | `_sources/mywms` | — |
| 默认分支 | master | git |
| 默认分支最后提交 | `db77e19` **2023-09-23** `Merge pull request #31` | git log |
| 仓库 `pushed_at` | 2026-04-14（非默认分支有推送） | GitHub API |
| Star / Fork / Watch | 133 / 46 / 17 | GitHub API |
| 主语言 | Java | GitHub API |
| 许可证 | **GPL v3**（`COPYING` 原文 "GNU GENERAL PUBLIC LICENSE Version 3"；`LICENSE` 说明为 myWMS LOS 的 fork） | 实证 |
| 仓库创建 | 2019-07-01 | GitHub API |

> 关于活跃度：默认分支 2023-09 之后再无提交，但 `pushed_at` 为 2026-04，说明有其他分支在动。本次只看了 master。

## 2. 它是什么 / 不是什么

**是**：myWMS LOS 的社区 fork，一套面向**人工仓**的完整 WMS。README 原文：

> "myWMS is a professional open source WMS ... It is running in industrial 24/7 environments and supports the basic processes of a warehouse."
> "Designed for manual driven warehouses"
> "The software is written in a multitear JEE architecture. The server component is running in a JEE application server like wildfly."

**不是**：

1. 不是 Spring 应用。它是 JEE EJB 应用（EJB + EAR），需要 WildFly 之类的应用服务器。
2. 不是纯 Web 系统。客户端是 NetBeans RCP 桌面程序和移动端 Web。

## 3. 技术栈

| 项 | 值 | 来源 |
|---|---|---|
| 架构 | JEE 8 多 tier（EJB + EAR） | README |
| 应用服务器 | WildFly 22 | README |
| JDK | Java 11 | README |
| 桌面客户端 | NetBeans RCP（`rich.client/los.clientsuite`） | 目录 + README |
| 远程调用 | SOAP（`los.ws-ejb`、`LOSxxxServiceRemote`） | 模块 + 类名 |
| 持久化 | JPA / EJB（`project-persistence` 模块） | 模块 |
| 构建 | Maven 9 个模块 | `dev/pom.xml` |

【实证】`dev/pom.xml` 的 module 列表：

```
mywms.as-ejb, los-ejb, los.ws-ejb, los.mobile-ejb, los.mobile-web,
wms2-ejb, project-ejb, project-persistence, project-ear
```

## 4. 代码规模

| 指标 | 值 |
|---|---|
| Java 文件 | 1,479 |
| Java 行数 | 176,272 |
| JPA 实体（`@Entity`） | 50 |
| SQL 文件 | 5（全部是 `db/upgrade-v2.0.0-*.sql` 升级脚本，**无初始 DDL**） |
| 部署物料 | 无 docker-compose / Dockerfile |

## 5. 功能结构与代码模块

### 5.1 功能结构（两端各一套，均来自目录实证）

**移动端（`dev/los.mobile-web/src/main/webapp/pages/processes/`）**——这是现场作业的功能清单：【实证】

```
goodsreceipt      收货
gr_direct         直接收货（免上架）
storage           上架/存储
picking           拣货
replenish         补货
shipping          发运
stocktaking       盘点
info              信息查询
login             登录
```

**桌面端（NetBeans RCP，`rich.client/los.clientsuite/` 下的模块名）**：【实证】

```
LOS Inventory Browser      库存浏览器
LOS Location Browser       库位浏览器
LOS Stocktaking Browser    盘点浏览器
LOS Processes              流程（作业执行）
LOS Report                 报表
LOS Data Importer          数据导入
LOS Common                 公共
```

**服务端业务域（`dev/wms2-ejb/src/main/java/de/wms2/mywms/`）**：【实证，并集】

```
address / client / product / inventory / location / picking / replenish
shipping / delivery / goodsreceipt / transport / document / strategy
sequence / report / property / user / module
```

三点可读出的设计：

1. **移动端就是作业流**（收货→上架→拣货→补货→发运→盘点），每个流程一个页面目录，与 PDA 作业一一对应。
2. **桌面端偏"看"**：三个 Browser（库存/库位/盘点）+ 报表 + 导入，说明桌面端是管理与查询，不是作业。
3. **服务端有一个 `strategy` 包**，与第 6 节的 `StorageStrategy` / `OrderStrategy` 实体对应，印证策略是独立子系统。

### 5.2 代码模块

```
mywms/
├── dev/
│   ├── wms2-ejb/            WMS 核心 EJB（地址/收货/库存/库位/拣货/补货/发运/运输/策略）
│   ├── los-ejb/             LOS 基础（库区/库位/容量/单元负载）
│   ├── los.ws-ejb/          SOAP 远程服务
│   ├── los.mobile-ejb/      移动端后端
│   ├── los.mobile-web/      移动端 Web
│   ├── mywms.as-ejb/
│   ├── project-ejb / project-persistence / project-ear
├── rich.client/
│   ├── los.clientsuite/     NetBeans RCP 客户端
│   └── los.reference/
├── db/                      5 个升级脚本
└── doc/
```

`wms2-ejb` 的业务包（【实证】）：

```
address / advice / client / delivery / document / goodsreceipt / inventory
location / module / picking / product / property / replenish / report
sequence / shipping / strategy / transport / user
```

## 6. 数据模型（50 个实体，最完整的一套）

【实证，`@Entity` 类清单按业务域归类】

**库位与库区**

```
StorageLocation        库位
StorageArea            库区
Zone                   区域
LocationType           库位类型
LocationCluster        库位簇
LocationReservation    库位预留
FixAssignment          固定货位分配
TypeCapacityConstraint 类型容量约束
```

**单元负载与库存**

```
UnitLoad               托盘/料箱（物流单元）
UnitLoadType
StockUnit              库存单元
ItemData / ItemDataArea / ItemDataNumber / ItemUnit
InventoryJournal       库存流水（日记账）
```

**作业单据**

```
GoodsReceipt / GoodsReceiptLine   收货
Advice / AdviceLine               通知（ASN）
PickingOrder / PickingOrderLine   拣货
ReplenishOrder                    补货
ShippingOrder / ShippingOrderLine 发运
DeliveryOrder / DeliveryOrderLine 配送
TransportOrder                    运输（仓内搬运）
LOSStocktakingOrder / Record      盘点
```

**策略**

```
OrderStrategy          订单策略
StorageStrategy        上架策略
StorageStrategyArea    策略-库区
LOSBom                 物料清单
```

`InventoryJournal` 这个命名说明库存变动是 **journal（日记账）** 模式，与 OpenBoxes 的 Transaction 同思路。【实证：类名】

## 7. 核心机制

1. **策略对象化 + 排序字段驱动**（W-07 已解）：`StorageStrategy` 不是开关，是一张表里的一组字段：

   ```java
   private Zone    zone;
   private int     orderByMode;          // 排序模式
   private boolean mixItem  = true;      // 允许混放商品
   private boolean mixClient = false;    // 允许混放货主
   private boolean onlyClientLocation;   // 只放本货主库位
   private boolean manualSearch;         // 人工指定
   private String  sorts;                // 排序字段列表（逗号分隔）
   private boolean nearPickingLocation;  // 靠近拣货位
   private boolean useAreaStrategyDate;  // 按区域策略日期
   private boolean useItemDataArea;      // 按商品-区域关系
   ```

   `StorageStrategySortType` 枚举定义 9 个可排序维度：【实证】

   ```
   CLIENT(0) / STORAGEAREA(1) / ZONE(2) / CAPACITY(3) / ALLOCATION(4)
   POSITION_X(5) / POSITION_Y(6) / NAME(7) / ORDERINDEX(8)
   ```

   `LocationFinderBean` 按 `sorts` 里列出的字段逐个拼排序（第 764-799 行 if-else 链），再用 `QUERY_LIMIT = 30` 只取前 30 个候选库位，配合 `fieldWeightBuffer` / `sectionWeightBuffer` 两个权重缓冲做细分。入口方法有 `findStorageLocation`（上架）、`findPickingLocation`（拣货）、`findAddToLocation`（同品合并）。【实证】

   **这套"策略表 + 排序字段串 + 权重 + 候选上限"是本次调研里唯一完整可读的库位选择实现**，比 Open WES 的"分配完成(库区)"更细。
2. **运输订单 + 事件**：`TransportOrder`、`TransportOrderType`、`TransportOrderStateChangeEvent`、`TransportOrderPartialProcessEvent`、`TransportEventObserver` —— 仓内搬运有独立状态与事件观察者。【实证：类名】
3. **库位容量约束**：`TypeCapacityConstraint` + `LocationType`，说明库位按类型有容量上限。【实证：类名】
4. **固定货位**：`FixAssignment`，支持"某商品固定放某库位"。【实证：类名】
5. **移动端与桌面端并存**：`los.mobile-web` + `rich.client/los.clientsuite`。【实证：目录】

## 8. 接口与集成

- SOAP：`los.ws-ejb` + 大量 `*ServiceRemote` 接口（`LOSAreaServiceRemote`、`LOSStorageLocationTypeServiceRemote`…）。【实证】
- 移动端 Web：`los.mobile-web`。【实证】
- 无 REST API、无消息队列（未发现相关依赖）。【实证：模块结构】

## 9. 部署方式

- 部署单元是 EAR（`project-ear`），需要 WildFly 22 + Java 11。【实证 + README】
- 仓库内**没有** Dockerfile / compose / K8s 清单。【实证】
- 数据库初始化：只有 5 个 `upgrade-v2.0.0-*.sql` 升级脚本，**没有初始建表脚本**。【实证：`db/` 目录】

## 10. 活跃度

- 默认分支最后提交 2023-09-23（约 3 年前）。【实证】
- `pushed_at` 2026-04-14，说明有其他分支活动。【API】
- 开放 issue 7 个。【API】

## 11. 对我们可参考 / 不建议参考

**建议参考（模型层面，价值最高的老项目）**

1. **50 个实体的领域划分**：库位（StorageLocation/StorageArea/Zone/LocationType/LocationCluster/FixAssignment/TypeCapacityConstraint）、单元负载（UnitLoad/UnitLoadType）、库存（StockUnit/InventoryJournal）分开。这是"库位不是库存的字段，而是独立聚合"的最佳示范。
2. **单元负载（UnitLoad）独立建模**：托盘/料箱是独立实体，库存挂在单元负载上。做托盘管理/容器管理时可直接对照。
3. **策略实体化**：`StorageStrategy` + `StorageStrategyArea` + `OrderStrategy`，策略不是代码里的 if-else，而是可配置数据。
4. **库存日记账**：`InventoryJournal` 的思路与 OpenBoxes 的 `Transaction` 一致，互相印证。

**不建议参考**

1. **技术栈**：EJB + EAR + WildFly + NetBeans RCP + SOAP，团队里几乎不会再选。
2. **没有初始 DDL**：只有升级脚本，建库要从别处找。
3. **许可证**：GPL-3.0。
4. **活跃度**：默认分支三年未动。

## 12. 未核实清单

1. 其他分支（2026-04 有推送）的内容 —— 未克隆。
2. 无初始建表脚本，表结构只能从 50 个实体反向推导 —— 未逐字段读。
3. `fieldWeightBuffer` / `sectionWeightBuffer` 两个权重的具体计算方式 —— 只确认字段存在。
4. 是否能在 WildFly 22 下跑起来 —— 未部署验证。
5. 移动端 Web 的技术栈 —— 未读 `los.mobile-web` 的依赖。
