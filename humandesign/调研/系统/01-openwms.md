# 01 OpenWMS.org 调研报告

> 取证时间 2026-11-24。所有结论可用 `_sources/` 下的源码与 `_facts/` 输出复核。

## 1. 基本信息

| 项 | 值 | 来源 |
|---|---|---|
| 主页仓库 | https://github.com/openwms/org.openwms | — |
| 本地目录 | `_sources/openwms-org`（+ `ow-common` / `ow-tms` / `ow-receiving` / `ow-movements`） | — |
| 默认分支 | master | git |
| 最后提交 | `9d263bf` 2026-07-13 `#185 development version 4.1.0-SNAPSHOT` | git log |
| Star / Fork / Watch | 765 / 223 / 51 | GitHub API |
| 主语言 | Java | GitHub API |
| 许可证 | Apache License 2.0（`LICENSE` 原文首行 "Apache License Version 2.0, January 2004"） | 实证 |
| 仓库创建 | 2015-01-04 | GitHub API |
| 开放 issue | 2 | GitHub API |

## 2. 它是什么 / 不是什么

**是**：一个按业务能力拆成多个独立部署服务的 WMS，附带物料流控制（MFC）。README 原文：

> "Is a free to use and extensible Warehouse Management System (WMS) with a Material Flow Control (MFC) system for automatic and manual warehouses."

**不是**：不是一个单体应用，也不是一个可以直接 `mvn spring-boot:run` 就跑起来的产品——主页仓库只是"根仓库 + 一个工具模块"，真正的服务在几十个独立仓库里。

**关键事实（必须先说）**：这些子仓库里，**有相当一部分只是启动壳**。

| 子仓库 | 本地 Java 文件数 | 依赖 | 结论 |
|---|---|---|---|
| `org.openwms.common.service` | 4（3 个类 + package-info） | `org.openwms.common.service.lib` | 领域实现不在仓库内 |
| `org.openwms.wms.receiving` | 2 | `org.openwms.wms.receiving.lib` | 同上 |
| `org.openwms.wms.movements` | 2 | `org.openwms.wms.movements.lib` + `common.service.lib` | 同上 |
| `org.openwms.tms.transportation` | 90 | 无 `*.lib` 依赖 | **完整实现** |

【实证】依据：各仓库 `src/main/java` 下文件清单 + `pom.xml` 的 `artifactId` 依赖项。jar 内是否附带 sources，本次未下载核实 →【未核实】。

README 的微服务表还显示：`wms.picking`、`wms.tasks`、`wms.inventory`、`gateway`、`auth` 等条目标注为 Private / Private Preview / GPLv3，**不全是 Apache-2.0 开源**。【自述 + 表格原文】

## 3. 技术栈（构建文件实证）

- Java **25**，Spring Boot **4.1**：README "# Technologies" 段原文；构建要求 "Building requires JDK 25"。【自述，未构建验证】
- Maven 多模块 + Maven Wrapper（`mvnw`）。【实证】
- 子服务 parent 版本：`org.openwms.parent` **27.2 / 27.7.1**（日期式版本）。【实证 `pom.xml`】
- 持久化：Jakarta Persistence API（JPA）；事件：RabbitMQ + Spring Integration；工作流引擎：Activiti / Flowable / Camunda（README 声明支持，本次未在克隆代码中看到相关配置）。【自述】
- 主页仓库唯一模块 `org.openwms.core.util`：64 个 Java 文件 / 3421 行。【实证】

## 4. 代码规模

| 范围 | Java 文件 | Java 行数 |
|---|---|---|
| 主页仓库（含 core.util） | 64 | 3,421 |
| tms.transportation | 90 | 未单独统计 |
| common.service / receiving / movements | 4 / 2 / 2 | 合计不足 300 行 |
| SQL 建表脚本 | **0 个 .sql 文件** | — |

没有 SQL 脚本意味着：数据库由 JPA/Liquibase 运行时生成，或由未公开的部署物料提供。【实证：`find -name '*.sql'` 返回 0】

## 5. 功能结构与代码模块

### 5.1 功能结构（按"服务即功能域"组织，可达性逐条标注）

OpenWMS 没有统一菜单，功能结构就是它的服务清单。表来自主页 README 的微服务表（原文），可达性列是该表自己标注的。【实证/自述混合，逐行标注】

```
基础设施
  Service Registry        org.openwms.services              Public  Apache-2.0
  Configuration Service   org.openwms.configuration         Public  Apache-2.0
  Gateway Service         org.openwms.gateway               Public  Apache-2.0
  Auth Service            org.openwms.auth                  Private GPLv3
主数据与基础
  Common Service          org.openwms.common.service        Public  Apache-2.0   （本地仅壳）
  Common Tasks Service    org.openwms.common.tasks          Public  Apache-2.0
  UAA Service             org.openwms.core.uaa              Public  Apache-2.0
  Preference / Printing   core.preferences / core.printing  Public  Apache-2.0
作业域
  Receiving Service       org.openwms.wms.receiving         Public  Apache-2.0   （本地仅壳）
  Putaway Service         org.openwms.wms.putaway           Public  Apache-2.0   （未克隆）
  Movements Service       org.openwms.wms.movements         Public  Apache-2.0   （本地仅壳）
  Picking Library         org.openwms.wms.picking           Private GPLv3
  Inventory Service       org.openwms.wms.inventory         Private Preview Apache-2.0
  WMS Tasks Service       org.openwms.wms.tasks             Private Preview Apache-2.0
  Shipping Service        org.openwms.wms.shipping          Private Preview Apache-2.0
运输与路由
  Transportation Service  org.openwms.tms.transportation    Public  Apache-2.0   （本地完整，90 个类）
  TMS Routing             org.openwms.tms.routing           Public  Apache-2.0
设备与外部
  Common Comm             org.openwms.common.comm           Public  Apache-2.0
  OPC UA Adapter          org.openwms.common.opcua          Private（interface21-io）
  SAP / MS Dynamics / NetSuite Adapter                        Private GPLv3
  WMS Orchestrator        org.openwms.wms.orchestrator      Private Preview Apache-2.0
```

可读出的结论：

1. **功能覆盖是完整的**：收货、上架、移库、拣货、库存、发运、任务、运输、路由、设备通信全有。
2. **但开源可达性不完整**：拣货（Picking）与 ERP 适配器是 Private GPLv3，库存/任务/发运是 Private Preview，Auth 也是 Private。**公开 Apache-2.0 且代码完整可读的，本次只确认了 `tms.transportation` 一个**。
3. **没有采购/销售/财务**，入口是"任务"（ERP 推单），定位与 Open WES 类似但抽象层次更高（以物流单元 + 运输任务为核心，而不是容器 + 工作站）。

运输服务的 REST 功能点（6 个端点）：【实证，`TransportationController`】

```
GET   /transport-orders?barcode=&state=     按条码+状态查
GET   /transport-orders/{pKey}              按主键查
POST  /transport-orders?barcode=&target=    按条码+目标创建
POST  /transport-orders                     创建
PATCH /transport-orders/{pKey}              更新
POST  /transport-orders/{pKey}?state=       状态变更
```

### 5.2 代码模块

主页仓库：

```
org.openwms/            # 根：文档 + 一个工具模块
├── doc/                # 架构图与文档
├── org.openwms.core.util/   # 共享工具、异常、事件类型
├── scripts/
└── pom.xml
```

公开子服务（本次实际克隆的四个）：

```
org.openwms.common.service     -> ow-common      (库位/物流单元等基础服务，壳)
org.openwms.tms.transportation -> ow-tms         (运输订单/任务，完整)
org.openwms.wms.receiving      -> ow-receiving   (收货，壳)
org.openwms.wms.movements      -> ow-movements   (移库，壳)
```

tms 内部包结构可直接读出设计意图：【实证，目录清单】

```
org.openwms.tms
├── TransportOrder.java / TransportOrderState.java / PriorityLevel.java
├── TransportationController.java      # 唯一 REST 入口
├── impl/
│   ├── state/       # 状态机：StateManagerImpl, ChangeState, Starter, StartListener,
│   │                #        InternalStartAcceptor, ExternalStarter, AmqpStateListener
│   ├── targets/     # 目标解析：LocationTargetHandler, LocationGroupTargetHandler
│   ├── redirection/ # 目标重定向：TargetRedirector, LocationRedirector, DecisionVoter
│   ├── removal/     # 物流单元移除校验
│   └── TransportationServiceImpl.java
├── api/             # messages / requests/state
├── commands / events
└── app/             # Spring 装配（Async / Feign / Security / Otlp）
```

## 6. 数据模型（可实证的部分）

仓库无建表脚本，实体类也只有 tms 可见。`TransportOrder` 字段（【实证】`ow-tms/src/main/java/org/openwms/tms/TransportOrder.java`）：

```
transportUnitBK   物流单元识别码（业务主键）
sourceLocation    起始库位（字符串）
targetLocation    目标库位
targetLocationGroup 目标库位组
priority          优先级（PriorityLevel，默认 NORMAL）
state             状态（默认 CREATED）
problem           问题描述（Message）
startDate / endDate
```

字段形态说明两点设计取向：【实证，字段类型可读】

1. 库位是用**字符串编码**引用，不是对象关联 —— 服务之间靠编码解耦。
2. 有 `targetLocationGroup`，说明"目标是一个库位组，由服务端选具体库位"，不是调用方指定到具体货位。

## 7. 核心机制

### 7.1 运输订单状态机（最有参考价值）

【实证】`TransportOrderState.java`，7 个状态，带排序值：

| 状态 | 排序 | 注释原文 |
|---|---|---|
| CREATED | 10 | Status of new created `TransportOrder`s |
| INITIALIZED | 20 | full initialized, ready to be started |
| STARTED | 30 | started and active, ready to be executed |
| INTERRUPTED | 40 | paused. Not active anymore |
| ONFAILURE | 50 | failure. Not active anymore |
| CANCELED | 60 | aborted. Not active anymore |
| FINISHED | 70 | completed successfully |

状态迁移不是散落在 service 里，而是集中在 `impl/state/` 下的一组成员（`ChangeState`、`StateManagerImpl`、`Starter`、`StartListener`），并通过 `AmqpStateListener` 接收外部状态变更消息。【实证：类清单 + 类名语义；具体调用链本次未逐行追踪 → 部分【未核实】】

### 7.2 目标解析与重定向分离

`TargetResolver` / `TargetHandler` 负责把"目标"解析成可执行库位；`impl/redirection/` 下是独立的重定向投票机制（`DecisionVoter`、`Vote`、`RedirectVote`、`LocationRedirector`）。这说明设计上把"选目标"和"改目标"分成两件事，且改目标可以多策略投票。【实证：类与包结构；投票聚合算法细节未读 →【未核实】】

### 7.3 优先级

`PriorityLevel` 与 `TransportStartComparator`、`PrioritizeTO` 共同存在，说明有"按优先级决定谁先启动"的比较器实现。【实证：类存在；排序规则未读】

## 8. 接口与集成

- REST：`TransportationController`（tms 内唯一 Controller）。【实证】
- 消息：`spring-boot-starter-amqp` + `AmqpStateListener` / `AmqpStartRequestForwarder`，状态经由 AMQP 传播。【实证：pom 依赖 + 类名】
- 服务间调用：`TransportationFeignConfiguration` → OpenFeign。【实证】
- 设备层：README 描述了 PLC / Raspberry Pi / Revolution Pi 的定位，并称有 OPC UA 适配（`org.openwms.common.opcua`，Private 仓库）。【自述，未取证】

## 9. 部署方式

- 每个服务是一个 Spring Boot 应用，README 称设计为跑在 AKS / EKS / OpenShift。【自述】
- 仓库内**没有** docker-compose、K8s manifest、Helm chart。【实证：`find` 无相关文件】
- 构建需 JDK 25 + `./mvnw clean verify`。【自述】

## 10. 活跃度

- 默认分支最后提交 2026-07-13。
- GitHub `pushed_at` 2026-07-13，开放 issue 2 个。
- 子服务版本号 27.2 / 27.7.1，说明仍在按版本迭代。【实证】

## 11. 对我们可参考 / 不建议参考

**建议参考（都能落到文件）**

1. **运输订单的独立生命周期**：`TransportOrder` 不是入库单/出库单的附属，而是独立聚合，用 `transportUnitBK` 关联物流单元。→ 我们的调拨/移库如果要独立跟踪"这一托货从 A 到 B"，可直接对照这套模型。
2. **状态机集中管理**：7 状态 + 排序值 + 独立 `state` 包，而不是在 Service 里 if-else 改状态。→ 对应 `impl/state/`。
3. **目标用"库位组"表达**：调用方给 `targetLocationGroup`，由服务端解析具体库位。→ 这正是"策略可配置"的落地方式。
4. **重定向作为独立扩展点**：`redirection/` 包把"改目标"从主流程里摘出来。

**不建议参考**

1. **模块拆分粒度**：一个业务能力一个独立仓库 + 独立数据库，运维成本高；且大量仓库是壳，跟进去会浪费时间。
2. **技术基线**：Java 25 + Spring Boot 4.1 是 2026 年的最前沿版本，生态与团队现状未必匹配。
3. **数据模型**：拿不到建表脚本，实体也大部分不在开源仓库内，不能作为表结构参考。

## 12. 未核实清单

1. `*.lib` 依赖 jar 内是否包含源码或完整领域模型 —— 未下载。
2. `wms.picking` / `wms.inventory` / `wms.tasks` / `gateway` / `auth` 的实际代码（README 标为 Private / Private Preview / GPLv3）—— 无权访问。
3. 项目是否能用 JDK 25 在本机构建成功 —— 未执行构建。
4. 数据库类型与表结构 —— 仓库内无 SQL、无迁移脚本。
5. AMQP 消息体格式与 topic 命名 —— 未读 `api/messages`。
6. `wms.putaway`、`wms.trucks`、`tms.routing` 等未克隆仓库的实现程度 —— 未取证（推测同样是壳，但**不作为结论**）。
