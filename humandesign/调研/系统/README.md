# 开源 WMS / WES 调研（Java 方向）

抓取时间 2026-11-24。方法与证据规则见 [00-调研方法与模板.md](./00-调研方法与模板.md)。

## 报告清单

| # | 报告 | 仓库 | 主语言 | 许可证（原文核实） | 默认分支最后提交 |
|---|---|---|---|---|---|
| 01 | [OpenWMS](./01-openwms.md) | openwms/org.openwms | Java | Apache-2.0 | 2026-07-13 |
| 02 | [JeeWMS](./02-jeewms.md) | erzhongxmu/JeeWMS | Java | GPL-3.0 | 2026-10-09 |
| 03 | [ruoyi-wms](./03-ruoyi-wms.md) | zccbbg/wms-ruoyi | Java | 修改版 Apache 2.0（多租户 + LOGO 限制） | 2026-03-20 |
| 04 | [Open WES](./04-open-wes.md) | jingsewu/open-wes | Java | MIT | 2026-11-23 |
| 05 | [OpenBoxes](./05-openboxes.md) | openboxes/openboxes | Groovy | EPL-1.0 | 2026-11-23 |
| 06 | [OMS-ERP](./06-oms-erp.md) | FJ-OMS/oms-erp | Java | Apache-2.0 | 2025-12-24 |
| 07 | [myWMS](./07-mywms.md) | wms2/mywms | Java | GPL-3.0 | 2023-09-23 |
| 08 | [SPMS-Server](./08-spms-server.md) | S-PMS/SPMS-Server | Java | MIT | 2026-10-14 |
| 09 | [openTCS](./09-opentcs.md) | openTCS/opentcs | Java | 多许可（REUSE，逐文件 SPDX） | 2026-09-17 |

## 待追清单

全部未核实项已汇总到 [99-追查清单.md](./99-追查清单.md)：**62 条，已完成 19 条**（P0 8 条 + P1 11 条，全部有结论），剩余 P2 20 条 + 需运行验证 9 条 + 追不到 14 条。

## 流程级文档（下钻到"动作 → 状态 → 表 → 事件"）

| 文件 | 覆盖内容 | 完整度 |
|---|---|---|
| [02a-jeewms-功能流程.md](./02a-jeewms-功能流程.md) | 入库（含月台）、出库（波次→下架→复核→发货）、移库、盘点、PDA 十动作映射、状态字典、**库存维护机制** | 已闭合：波次写行字段无波次表、发货单仅手工 CRUD、下架 8 步、`bin_sta` 四值语义、库存由存储过程 `update_wm_his_stock` + 视图维护（不在 Java 层）。仍缺：存储过程与视图定义不在仓库 |
| [04a-open-wes-功能流程.md](./04a-open-wes-功能流程.md) | 入库、出库（分配四分支→Redis 队列→定时分波→拣货→库存扣减）、库存四账户、事件投递、盘点/调整、设备任务 | **已闭合**：入库与出库两个方向的设备任务、库存记账规则（四账户恒等式+乐观锁重试）、事件介质（落表+afterCommit+Guava EventBus）、盘点差异链路。仍缺：设备回调协议、`d_domain_event` 是否被重放消费 |

两份文档都画了 Mermaid 时序图 + 逐步对照表（代码位置/落库/状态/事件），并单列"未核实"。

## 功能结构的证据来源（每份报告第 5 节）

功能结构不能靠"看名字猜"，本批各系统的取证条件差异很大，来源逐条标注：

| 系统 | 功能结构来源 | 完整度 |
|---|---|---|
| Open WES | `init_menu.sql` 的 209 条菜单（目录/菜单/按钮三级） | **最高**，可直接还原菜单树 |
| OpenBoxes | Controller 域 + View 目录双向交叉 | 高，一个 Controller 对应一组页面 |
| myWMS | 移动端流程页面目录 + 桌面端模块名 + EJB 包 | 高，两端各自独立取证 |
| OMS-ERP | 前端 `views` 三级目录 | 高，但页面名 ≠ 后端能力 |
| SPMS-Server | `module` 目录 + README 模块表对照 | 中，WMS 只有 4 个单据 |
| JeeWMS | PDA `CONSTANTTYPE` 33 种作业类型 + 实体域 + 前端 182 个生成页 | 中，菜单在数据库内、不可解析 |
| ruoyi-wms | 16 个 Controller 的 `@RequestMapping` 路径 | 中，前端在另一仓库、无菜单数据 |
| OpenWMS | README 微服务表（服务即功能域）+ tms 6 个端点 | 低，大部分服务是壳或私有 |
| openTCS | 桌面端菜单 properties + 模块名 | 中，桌面应用无 Web 菜单 |

> 教训：有菜单初始化 SQL 的项目（Open WES）才能给出"真实菜单树"；其余只能给"接口/页面/实体功能域"。三类东西不等价，报告里已分别标注。

## 横向对照（仅列已实证的差异）

### 系统类型

| 系统 | 实际类型 | 依据 |
|---|---|---|
| OpenWMS | WMS + 物料流控制 | README + tms 服务代码 |
| JeeWMS | WMS + OMS + BMS + TMS（单体为主） | 119 个业务实体、计费包、发货单 |
| ruoyi-wms | 轻量 WMS（无库位） | 16 张 `wms_*` 表，无库区/库位表 |
| Open WES | **WES**（执行层） | 波次、任务、工作站、设备任务表 |
| OpenBoxes | 医疗 WMS | 领用/召回/批次效期模型 |
| OMS-ERP | **OMS + 库存中心，不是 WMS** | 库存表只有仓库+SKU+在库量 |
| myWMS | 人工仓 WMS | 50 个实体含库位/单元负载/策略 |
| SPMS-Server | MES 为主，WMS 为模块 | `module.wms` 仅 26 个类 |
| openTCS | **AGV 车队调度（WCS）** | Router/Dispatcher/Scheduler + VDA5050 |

### 技术栈与规模

| 系统 | 框架 / JDK | Java 文件 | Java 行数 | 表 / 实体 |
|---|---|---|---|---|
| OpenWMS | Spring Boot 4.1 / JDK 25（自述） | 主页 64 + tms 90 | 3,421（主页） | 无 SQL；tms 实体可见 |
| JeeWMS | Spring 4.0.9 + Hibernate 4.1 / JDK 8；另有 base-boot 2.4.6 底座 | 2,463 | 377,698 | 119 实体；无建表 SQL |
| ruoyi-wms | Spring Boot 3.2.6 / JDK 17 | 486 | 40,076 | 16 张业务表 |
| Open WES | Spring Boot 3.2.2 / JDK 17 | 1,505 | 76,503 | 67 张表 |
| OpenBoxes | Grails 3.3.16 / JDK 8 | 0（Groovy 1,348） | — | 119 领域类 / 228 Liquibase |
| OMS-ERP | Spring Cloud（版本在外部 parent） | 1,171 | 74,596 | 23 张表 |
| myWMS | JEE 8 + WildFly / JDK 11 | 1,479 | 176,272 | 50 个 `@Entity`；无初始 DDL |
| SPMS-Server | Spring Boot 3 + AirPower4J 9 / JDK 17 | 344 | 21,608 | 0 SQL |
| openTCS | JDK 21 + Gradle | 2,125 | 274,766 | 工厂模型，非关系模型 |

### 关键能力有无（有 = 代码/表实证，无 = 未发现）

| 系统 | 库位 | 批次/效期 | 任务层 | 波次 | 设备对接 | 多货主 |
|---|---|---|---|---|---|---|
| OpenWMS | 有（LocationTargetHandler） | 未核实 | 有（TransportOrder） | 无 | 有（AMQP/OPCUA 自述） | 未核实 |
| JeeWMS | 有（`wm_kw`/BaKw） | 有（WmHisStock 等） | 有（WmToUp/Down/Move） | 有（PDA 波次下架/波次分拣） | 自述（RFID/AGV 未定位代码） | 自述 |
| ruoyi-wms | **无** | 无（lite 分支） | 无 | 无 | 无 | 无 |
| Open WES | 有（`w_location`） | 有（`w_sku_batch_stock`） | 有（`w_operation_task`） | 有（`w_outbound_wave`） | 有（`e_container_task`） | 有（`m_owner_main_data`） |
| OpenBoxes | 有（Location） | 有（lotNumber/expirationDate） | 无 | 无 | 无 | 无 |
| OMS-ERP | 无（仅库区） | 无 | 无 | 无 | 无 | 有（`tenant_id`） |
| myWMS | 有（StorageLocation 等 8 个类） | 未核实 | 有（TransportOrder） | 无 | 无 | 无 |
| SPMS-Server | 无 | 无 | 无 | 无 | IoT 上报 | 未核实 |
| openTCS | 不适用 | 不适用 | 有（DriveOrder） | 无 | 核心能力 | 不适用 |

> 空格里的"无"仅代表**本次在代码/表中未发现**，不等于项目文档里的否定声明。

### 许可证风险提示（只列事实，不做法律结论）

- **GPL-3.0**：JeeWMS、myWMS。闭源分发或 SaaS 化前需法务确认。
- **修改版 Apache 2.0**：ruoyi-wms，明确禁止未经授权的多租户运营，且不得移除 LOGO/版权，另有外观专利声明。
- **EPL-1.0**：OpenBoxes，弱 copyleft。
- **多许可证**：openTCS，需逐文件看 SPDX 头。
- **MIT / Apache-2.0**：Open WES、SPMS、OpenWMS、OMS-ERP（仍建议核对第三方依赖许可）。

## 怎么读

- 想看**执行层怎么建模**（波次、任务、工作站、设备任务）→ 04 Open WES。
- 想看**库位/单元负载/策略怎么抽象** → 07 myWMS（模型）、01 OpenWMS（服务拆分与状态机）。
- 想看**国内 3PL 的字段与单据流** → 02 JeeWMS。
- 想看**最小可用表结构** → 03 ruoyi-wms。
- 想看**批次/效期/事务账** → 05 OpenBoxes。
- 想看**多租户库存表与渠道模型** → 06 OMS-ERP（注意它不是 WMS）。
- 想看**设备调度算法** → 09 openTCS。
- 想看**现场终端怎么选** → 同级目录 [PDA](../PDA/README.md)。

## 本批未覆盖

1. 未做构建与运行验证（任何项目）。报告中所有"能跑"均为 README 自述。
2. 未评估性能、并发能力、生产可用性。
3. 未覆盖非 Java 项目（如 PHP/Python 的 WMS）与商业产品。
4. 未统计 issue 质量、社区响应速度、贡献者构成。
5. GitHub 上标 `wms` topic 的项目中约半数是 GIS 的 Web Map Service（如 GeoServer），已排除，未列入本批。
