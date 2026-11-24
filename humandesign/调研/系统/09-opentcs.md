# 09 openTCS 调研报告

> 取证时间 2026-11-24。源码在 `_sources/opentcs`（28 MB，浅克隆）。

## 1. 基本信息

| 项 | 值 | 来源 |
|---|---|---|
| 仓库 | https://github.com/openTCS/opentcs | — |
| 本地目录 | `_sources/opentcs` | — |
| 默认分支 | master | git |
| 最后提交 | `742b0e6` 2026-09-17 `Add support for background images in plant models` | git log |
| Star / Fork / Watch | 607 / 202 / 18 | GitHub API |
| 主语言 | Java | GitHub API |
| 许可证 | GitHub 识别为 `None`，实际为 **REUSE 规范的多许可证**：`LICENSES/` 下有 Apache-2.0、LGPL-2.1-only、MIT、CC-BY-4.0、CC0-1.0、OFL-1.1，文件头逐个标 SPDX | 实证 |
| 仓库创建 | 2021-07-26 | GitHub API |
| 开放 issue | 9 | GitHub API |

> 许可证需要逐个文件看 SPDX 头，不能一句话概括。本次只确认了 `LICENSES/` 目录内容与 `README.adoc` 的 `CC-BY-4.0` 头。

## 2. 它是什么 / 不是什么

**是**：AGV / 移动机器人**车队调度平台**（WCS 层，不是 WES）。README.adoc 原文：

> "openTCS (short for open Transportation Control System) is a free platform for controlling fleets of automated guided vehicles (AGVs) and mobile robots."
> "Primarily, openTCS is a framework / an implementation of the basic data structures and algorithms (routing, dispatching, scheduling) needed for running an AGV system with more than one vehicle."

由弗劳恩霍夫物流研究所（Fraunhofer IML）维护。

**不是**：

1. 不是 WMS，也不是 WES。没有库存、没有单据、没有波次。
2. 不是开箱可用的产品。它是框架 + 参考实现，接具体车型需要写通信适配器（communication adapter）。

## 3. 技术栈

| 项 | 值 | 来源 |
|---|---|---|
| JDK | **21**（README：推荐 Adoptium 发行版） | README.adoc |
| 构建 | Gradle（`gradlew`、`settings.gradle`） | 根目录 |
| 模块 | 20 个 Gradle 模块 | `settings.gradle` |
| 通信协议 | **VDA5050**（内置 `opentcs-commadapter-vda5050`，284 个 Java 文件） | 模块 + README |
| 桌面客户端 | Swing（`opentcs-operationsdesk`、`opentcs-kernelcontrolcenter`、`opentcs-modeleditor`） | 模块名 |
| 依赖注入 | Guice（`opentcs-api-injection`） | 模块名 |
| Lombok | `lombok.config` | 根目录 |

## 4. 代码规模

| 指标 | 值 |
|---|---|
| Java 文件 | 2,125 |
| Java 行数 | 274,766 |
| Gradle 模块 | 20 |
| VDA5050 适配类 | 284 |

是本批调研中**代码量最大**的项目。

## 5. 功能结构与代码模块

### 5.1 功能结构（桌面端菜单 + 内核能力）

openTCS 是桌面应用，没有 Web 菜单表。功能结构由三个客户端 + 一个内核服务构成，下面是能实证到的部分。

**操作台 Operations Desk**（`opentcs-operationsdesk`）——面向调度员：【实证，`i18n/.../mainMenu.properties` 的菜单 key 与文本】

```
视图 (View)
  Add course view               路径视图
  Add transport order view      运输订单视图
  Add order sequence view       订单序列视图
  Add peripheral job view       外围作业视图
  Reset window arrangement      重置窗口布局
动作 (Actions)
  Ignore orientation angle      忽略朝向角
  Ignore precise position       忽略精确位置
连接
  Connect to kernel             连接内核
```

**内核控制台 Kernel Control Center**（`opentcs-kernelcontrolcenter`）——面向运维：【实证，`Bundle.properties` 的界面文本】

```
车辆面板
  通用状态 General status
  车辆状态 Vehicle status
  适配器状态 Adapter status
  消息 Messages（全部 / 仅错误 / 错误+警告 / 清除）
  切换驱动（会重置该车的资源分配）
```

**工厂模型编辑器**（`opentcs-modeleditor`）：编辑点位、路径、位置、背景图（最后一次提交即"plant models 支持背景图"）。【实证：模块名 + 提交信息】

**地图总览**（`opentcs-plantoverview-*`）：可插拔面板——资源分配面板（`panel-resourceallocation`）、负载生成面板（`panel-loadgenerator`）、主题（`themes-default`）。【实证：模块名】

**内核服务**（`opentcs-kernel` + `opentcs-strategies-default`）：调度能力本身，见第 7 节。

三点结构特征：

1. **"视图"是用户自定义的**：不是固定页面，而是"添加一个运输订单视图/订单序列视图/外围作业视图"，可同时开多个。这是调度台工具（而非业务系统）的典型交互。
2. **车辆状态与消息是运维主界面**：控制台的核心是"看车、看适配器、看消息"，说明它的日常使用场景是设备运维，不是业务操作。
3. **没有业务单据概念**：运输订单（TransportOrder）是唯一"单据"，且只描述"从哪到哪"。

### 5.2 代码模块

【实证，`settings.gradle`】

```
opentcs-api-base                 核心接口（车辆、订单、路由、调度）
opentcs-api-injection            Guice 注入约定
opentcs-common                   公共工具
opentcs-impl-configuration-gestalt
opentcs-kernel                   内核（调度循环）
opentcs-strategies-default       默认策略实现（路由/分配/停车）
opentcs-commadapter-loopback     回环通信适配器（测试用）
opentcs-commadapter-vda5050      VDA5050 协议适配
opentcs-peripheralcommadapter-loopback
opentcs-kernel-extension-http-services   HTTP 扩展
opentcs-kernel-extension-rmi-services    RMI 扩展
opentcs-kernelcontrolcenter      内核控制台
opentcs-operationsdesk           操作台
opentcs-modeleditor              工厂模型编辑器
opentcs-plantoverview-base / -common / -panel-loadgenerator / -panel-resourceallocation / -themes-default
opentcs-documentation
```

## 6. 数据模型

`opentcs-api-base` 里的核心接口：【实证】

```
Dispatcher              车辆任务派发
DispatcherService
PeripheralDispatcherService / PeripheralJobDispatcher  外围设备作业派发
Router / RouterService  路径计算
Scheduler               调度（时间/资源）
```

工厂模型（plant model）是它的"数据模型"：点位（Point）、路径（Path）、位置（Location）、车辆（Vehicle）、外围设备（Peripheral）。具体类未逐个读 →【未核实】。

## 7. 核心机制

### 7.1 三段式调度

`Dispatcher` + `Router` + `Scheduler` 三接口分离，对应"派给谁 / 怎么走 / 什么时候走"。【实证：接口存在】

### 7.2 策略以"阶段（Phase）"组织

`opentcs-strategies-default` 的实现类名直接暴露调度循环的阶段划分：【实证】

```
AssignFreeOrdersPhase              分配自由订单
AssignReservedOrdersPhase          分配已预留订单
AssignSequenceSuccessorsPhase      分配序列后继
AssignFreePeripheralsPhase         分配外围设备作业
AssignReservedPeripheralsPhase
AllocatorTask / AllocatorCommand / AllocationAdvisor   分配决策
AreaAllocator / AreaAllocations / AreaAllocationModule 区域分配
AbstractReroutingStrategy / ...    重路由策略
BellmanFordPointRouterFactory      最短路（Bellman-Ford）
AbstractParkingPhase / AbstractParkingPositionSupplier 停车策略
```

这套"阶段 + 策略接口"的组织方式，比在一个 `Dispatcher` 里写 if-else 要清晰得多。【实证：类清单；执行顺序未逐行追踪】

### 7.3 VDA5050 内置

`opentcs-commadapter-vda5050` 有 284 个类，说明 VDA5050 是**一等公民**而不是示例。【实证：文件数】

### 7.4 内核怎么跑起来：组件装配，不是主循环（W-19 已解）

【实证】`opentcs-kernel/.../KernelStateOperating.java:206 initialize()`

```
① 重置所有车辆：ProcState=IDLE、IntegrationLevel=TO_BE_RESPECTED、
                  State=UNKNOWN、清空 transportOrder 与 orderSequence
② scheduler.initialize()              调度器
③ router.initialize()                 路由器
④ dispatcher.initialize()             派发器
⑤ peripheralJobDispatcher.initialize() 外围作业派发
⑥ vehicleControllerPool.initialize()   车辆控制器池
⑦ peripheralControllerPool.initialize()
⑧ attachmentManager / peripheralAttachmentManager
⑨ pathLockListener / vehicleDispatchTrigger
⑩ kernelExecutor.scheduleAtFixedRate(workingSetCleanupTask, ...)  周期清理
⑪ 各 KernelExtension.initialize()
```

`StandardKernel.run()` 只有一件事：`terminationSemaphore.acquireUninterruptibly()` 等终止信号，然后依次 `extension.terminate()`。**没有 while 轮询主循环**。

调度是"各组件自己驱动"：`Dispatcher` 由 `vehicleDispatchTrigger` 触发，`Scheduler` 管资源分配时间窗，`Router` 算路径。所以 openTCS 的架构是"组件 + 触发 + 线程池"，不是单线程 tick 循环。

这条对我们要不要借鉴很关键：如果团队习惯"一个定时任务扫全表"的写法，openTCS 这套组件化调度不好照抄。

## 8. 接口与集成

- HTTP：`opentcs-kernel-extension-http-services`。【实证：模块】
- RMI：`opentcs-kernel-extension-rmi-services`。【实证：模块】
- 车辆通信：communication adapter 插件式（`commadapter-loopback` 是测试实现）。【实证：模块】
- 外围设备：`peripheralcommadapter-loopback`。【实证：模块】

## 9. 部署方式

- 桌面应用（Swing）+ 内核服务，不是 Web 应用。【实证：模块形态】
- 无 Dockerfile / compose。【实证：`find` 为空】
- 需要 JDK 21 + Gradle 构建。【实证】

## 10. 活跃度

- 最后提交 2026-09-17，`pushed_at` 2026-09-17。
- 开放 issue 9 个。【GitHub API】
- 由研究机构团队维护，提交者署名 `Martin Grzenia` 等。【实证】

## 11. 对我们可参考 / 不建议参考

**建议参考**

1. **Dispatcher / Router / Scheduler 三接口分离**：如果我们的 WES 要往下发设备任务，这三个职责的边界可以直接借用。见 `opentcs-api-base`。
2. **调度阶段（Phase）化**：把"分配订单"拆成 `AssignFreeOrdersPhase` / `AssignReservedOrdersPhase` / `AssignSequenceSuccessorsPhase`，每一步可插拔。见 `opentcs-strategies-default`。
3. **VDA5050 适配层参考**：如果要对接 AGV 厂商，这是现成的协议实现参考（284 个类）。
4. **回环适配器（loopback）**：没有真车也能测调度，这个思路值得抄。

**不建议参考**

1. **不是 WMS/WES**：没有库存与单据，不能作为业务系统参考。
2. **桌面技术栈**：Swing + Guice，与 Web 技术栈差异大。
3. **许可证**：多许可证 + 逐文件 SPDX，商用前须逐文件确认。

## 12. 未核实清单

1. 工厂模型的具体类结构与持久化方式 —— 未读 `opentcs-api-base` 的 model 包。
2. VDA5050 适配的完整度（支持到哪个版本）—— 未读。
3. 是否能构建运行 —— 未执行 Gradle。
4. 各文件的 SPDX 许可证分布 —— 只看了 `LICENSES/` 目录，未逐文件统计。
5. 各组件内部线程池的并发度与背压 —— 未读 `vehicleDispatchTrigger` 与 `Dispatcher` 的线程配置。
