# 08 SPMS-Server 调研报告

> 取证时间 2026-11-24。源码在 `_sources/spms-server`。这是一个 **MES 为主、WMS 为模块**的项目，列入是因为它常被当作"Java 开源 WMS"检索到。

## 1. 基本信息

| 项 | 值 | 来源 |
|---|---|---|
| 仓库 | https://github.com/S-PMS/SPMS-Server（API 返回 `s-pms/SPMS-Server`，仓库名已变更） | — |
| 本地目录 | `_sources/spms-server` | — |
| 默认分支 | main | git |
| 最后提交 | `23a4b0b` 2026-10-14 `fix(bugs): 修复了一些已知的BUG` | git log |
| Star / Fork / Watch | 340 / 82 / 4 | GitHub API |
| 主语言 | Java | GitHub API |
| 许可证 | MIT（`LICENSE` 原文 "MIT License / Copyright (c) 2022 Hamm"） | 实证 |
| 仓库创建 | 2024-03-27 | GitHub API |

## 2. 它是什么 / 不是什么

**是**：面向中小型制造企业的**生产管理系统**（MES 为主），把 MES / WMS / IoT 等模块装在同一个 Spring Boot 应用里。README 原文：

> "一个集成 MES / WMS / ERP / QMS / IoT 一体的企业级智能生产管理平台后端。"

**不是**：

1. 不是独立 WMS。WMS 只是 `module.wms`，只有入库单、出库单、移库单、库存四块。
2. **不是微服务**。所有模块在一个 Spring Boot 应用内，通过模块分包隔离。
3. README 自称含 ERP / QMS / MCP，但代码目录里**没有** `erp`、`qms`、`mcp` 包。【实证：`src/main/java/cn/hamm/spms/module` 下只有 asset / channel / chat / factory / iot / mes / open / personnel / system / wms】

## 3. 技术栈

| 项 | 值 | 位置 |
|---|---|---|
| JDK | 17 | README 徽章 `Lang: Java17` |
| Spring Boot | 3.x | README 徽章 `Base: SpringBoot3`（具体版本在 AirPower 父 POM） |
| 基础框架 | **AirPower4J**（`cn.hamm:airpower:9.0.0`） | `pom.xml` parent |
| ORM | Spring Data JPA | README 徽章 `ORM: JPA` |
| 数据库 | MySQL 8 | README 徽章 |
| 构建 | Maven + wrapper | `mvnw` |
| 部署 | Dockerfile + `deploy.sh` | 根目录 |

`pom.xml` 依赖的 AirPower 组件：【实证】

```
airpower-curd / airpower-file / airpower-mqtt / airpower-open
airpower-email / airpower-websocket
```

## 4. 代码规模

| 指标 | 值 |
|---|---|
| Java 文件 | 344 |
| Java 行数 | 21,608 |
| WMS 模块类 | 26（`module/wms` 下） |
| WMS Controller | 4（Input / Output / Move / Inventory） |
| SQL 建表脚本 | **0**（依赖 JPA 自动建表或外部初始化） |
| 模块 | 10 个业务包 |

## 5. 功能结构与代码模块

### 5.1 功能结构（代码目录与 README 逐条对照）

代码里有 10 个模块，README 列了 12 个，差异见下表。【实证：`module` 目录；对照：README 模块表】

```
system      菜单 / 权限 / 编码规则 / 系统配置 / 单位 / 文件 / 索引 / 企业微信回调
            （代码子包：unit file config status menu coderule permission）
personnel   用户 / 角色 / 部门
            （role user department）
asset       物料 / 设备 / 合同
            （contract device material）
channel     客户 / 供应商 / 采购 / 销售 / 采购单价 / 销售单价
            （sale saleprice supplier purchaseprice purchase customer）
factory     仓库 Storage / 生产单元 Structure（树形）
            （storage structure）
mes         生产计划 / 生产订单 / 领料单 / BOM / 工序 / 工艺路线
            （operation order bom plan picking routing）
wms         入库单 / 出库单 / 移库单 / 库存
            （input output move inventory）
iot         参数管理 + 设备数据上报（MQTT + Redis + InfluxDB）
            （parameter report）
chat        房间 / 成员 / WebSocket 事件
open        OAuth2 / 第三方登录 / 通知钩子 / 开放应用
            （notify oauth app thirdlogin api）
```

**README 有、代码里没有的模块**：`mcp`（AI 工具注册中心）、`wechat`（微信）。

WMS 模块的功能结构（4 个单据，无子功能）：【实证】

```
入库单 input       InputEntity + InputDetailEntity + InputStatus(AUDITING/REJECTED/INPUTTING/DONE)
出库单 output      OutputEntity + OutputDetailEntity + OutputStatus
移库单 move        MoveEntity + MoveDetailEntity + MoveStatus
库存   inventory   InventoryEntity + InventoryType
```

注意：**库存没有"库存流水/事务"类**，只有 `InventoryEntity`。这与 02/05/07（都有库存历史表）形成对比，说明它是"直接改数量"的简化实现。【实证：wms 包内类清单】

### 5.2 代码模块

【实证，`src/main/java/cn/hamm/spms/module`】

```
asset       资产（合同 / 设备 / 物料）
channel     渠道
chat        聊天（房间 / 成员 / WebSocket）
factory     工厂
iot         物联网（设备参数 / 数据上报）
mes         生产（计划 / 订单 / BOM / 工序 / 工艺路线 / 领料）
open        开放平台（OAuth2 / 第三方登录 / 通知钩子）
personnel   人员
system      系统
wms         仓储（入库 / 出库 / 移库 / 库存）
```

WMS 模块内部：【实证】

```
wms/
├── input/    InputEntity, InputDetailEntity, InputService, enums/InputStatus, InputType
├── output/   OutputEntity, OutputDetailEntity, OutputService, enums/OutputStatus, OutputType
├── move/     MoveEntity, MoveDetailEntity, MoveService, enums/MoveStatus
├── inventory/ InventoryEntity, InventoryService, InventoryType
└── WmsServices.java
```

每个业务只有"实体 + 明细实体 + Service + 状态枚举 + 类型枚举"，没有独立的任务层、策略层。

## 6. 数据模型

仓库无 SQL 脚本，只能从实体类读字段。`InputEntity`（入库单）与 `InputDetailEntity`（入库单行）分离，`InputStatus` 枚举：【实证】

```java
AUDITING(1, "审核中")
REJECTED(2, "已驳回")
INPUTTING(3, "入库中")
DONE(4, "已完成")
```

`OutputStatus` / `MoveStatus` 结构相同。【实证：文件存在】

库存实体 `InventoryEntity` + `InventoryType` 枚举。【实证】

**没有**：库位表、库区表、批次表、货主表、任务表。【实证：wms 包内类清单】

## 7. 核心机制

1. **单据带审批流**：状态从 `AUDITING`（审核中）开始，可 `REJECTED`，然后才 `INPUTTING` → `DONE`。这是**审批式单据**，不是仓储作业式单据。MES 场景下合理，仓储场景下缺"已收货/已上架"这类作业态。
2. **单层 Service**：`InputService` 直接操作实体，无领域层、无事件。【实证：包结构】
3. **JPA 自动建表**：没有 SQL 脚本，说明依赖 Hibernate DDL 或首次启动生成。【实证：0 个 sql 文件；是否配置 `ddl-auto` 未读 →【未核实】】

## 8. 接口与集成

- REST：4 个 WMS Controller + 其他模块 Controller，共 344 个类中的一部分。【实证】
- IoT：`module.iot` 用 MQTT + Redis + InfluxDB（README 声明）。【自述】
- 开放平台：`module.open` 提供 OAuth2 / 第三方登录。【实证：包存在】
- 无 ERP/SAP 对接模块。【实证：无对应包】

## 9. 部署方式

- 根目录 `Dockerfile` + `deploy.sh`。【实证】
- 单应用部署，依赖 MySQL 8（+ IoT 场景的 InfluxDB / MQTT）。【实证 + 自述】
- 配套仓库：`SPMS-Docker`（部署）、`SPMS-Docs`（文档）—— 均不在本仓库。【自述】

## 10. 活跃度

- 最后提交 2026-10-14，`pushed_at` 2026-10-14。
- 开放 issue 1 个。【GitHub API】
- 提交信息为中文小修（`fix(bugs): 修复了一些已知的BUG`）。【实证】

## 11. 对我们可参考 / 不建议参考

**建议参考**

1. **极简单据模型**：入库/出库/移库三张单 + 明细 + 状态枚举 + 类型枚举，26 个类支撑 WMS 四件事。可以作为"最小 WMS 原型"的骨架。见 `module/wms`。
2. **AirPower4J 的模块化组织方式**：单应用 + 按业务分包 + 每个包自带 Controller/Service/Entity/Enums，适合小团队快速起量。
3. **README 的结构化表达**（模块表 + 技术栈徽章）值得借鉴到我们自己的文档。

**不建议参考**

1. **不是 WMS 参考**：没有库位、批次、任务、策略，只适合当"MES 里的物料出入库"。
2. **无 SQL 脚本**：拿不到表结构。
3. **README 与代码不一致**：声称 ERP / QMS / MCP，代码里没有。
4. **状态设计**：审批态与作业态混在一个枚举里，且用 int 码值。

## 12. 未核实清单

1. Spring Boot 具体版本 —— 在 AirPower 父 POM 中，本仓库未包含。
2. JPA 建表策略（`ddl-auto`）—— 未读 `application.yml`。
3. IoT 的 MQTT / InfluxDB 配置 —— 未读。
4. `SPMS-Docs` / `SPMS-Docker` 两个配套仓库的内容 —— 未克隆。
5. 项目能否启动 —— 未执行。
