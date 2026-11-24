# 06 OMS-ERP 调研报告

> 取证时间 2026-11-24。源码在 `_sources/oms-erp`。**本项目严格来说不是 WMS**，列入调研是因为它的库存中心常被当作 WMS 参考，需要把边界说清楚。

## 1. 基本信息

| 项 | 值 | 来源 |
|---|---|---|
| 仓库 | https://github.com/FJ-OMS/oms-erp | — |
| 本地目录 | `_sources/oms-erp` | — |
| 默认分支 | main | git |
| 最后提交 | `34d8852` 2025-12-24 `Update README.md`（作者显示为"厦门飞骥科技有限公司"） | git log |
| Star / Fork / Watch | 1,932 / 366 / 89 | GitHub API |
| 主语言 | Java | GitHub API |
| 许可证 | Apache License 2.0（`LICENSE` 原文首行） | 实证 |
| 仓库创建 | 2022-05-27 | GitHub API |

> 注意：最后一条提交只改了 README，代码层的最后实质变更时间无法从浅克隆判断 →【未核实】。

## 2. 它是什么 / 不是什么

**是**：全渠道电商 OMS（订单管理系统）+ 库存中心（共享库存池）+ 渠道/商品/售后等模块。README 原文定位："开源的新零售业务中台OMS""全渠道一盘货订单管理系统OMS"。

**不是**：

1. **不是 WMS**。没有库位、没有作业任务、没有上架/拣货/波次。库存表粒度为"仓库 + SKU"。
2. 不是多仓履约调度系统。库存只在仓库级汇总，没有仓内位置概念。

【实证】判断依据：`sstock_goods_stock_detail` 表结构里只有 `warehouse_code` / `sku_code` / `stock_num`，无库位、批次、效期、预占字段。

## 3. 技术栈

| 项 | 值 | 位置 |
|---|---|---|
| 构建 | Maven 多模块，parent `skyer-parent:1.0.0-SNAPSHOT` | `skyer-stock/pom.xml` |
| 微服务 | Spring Cloud（每个 `skyer-*` 一个可部署服务） | 模块结构 |
| 注册配置 | Nacos（README topics：nacos；`skyer-gateway`） | GitHub topics |
| ORM | MyBatis Mapper 风格（`skyer-starter-mybatis-mapper`） | `skyer-stock/pom.xml` |
| 缓存 | Jedis 2.9.0 | `skyer-stock/pom.xml` |
| 搜索 | Elasticsearch（`StockEsService`） | 类存在 |
| 对象映射 | MapStruct 1.4.1 | `skyer-stock/pom.xml` |
| JDK | 未在顶层 pom 固定（无根 pom），子模块继承 parent →【未核实】 |

具体 Spring Boot / Spring Cloud 版本号在 `skyer-parent` 中，本仓库未包含该 parent 的 pom →【未核实】。

## 4. 代码规模

| 指标 | 值 |
|---|---|
| Java 文件 | 1,171 |
| Java 行数 | 74,596 |
| Vue 文件 | 387（`front-end/`） |
| Controller | 64 |
| 建表语句 | 23 |
| 模块 | 7 个后端 + 1 个前端 |

## 5. 功能结构与代码模块

### 5.1 功能结构（前端页面目录实证）

前端 `front-end/src/views` 按"业务中心 / 设置中心 / 公共"三段划分，业务中心的二级、三级目录就是功能结构。【实证：目录清单】

```
center-business 业务中心
├── order        订单
│   ├── internal / third     内部订单 / 第三方平台订单
│   └── abnormal             异常订单
├── goods        商品
│   ├── spu / sku / brand / classify / material / price / virtual
│   └── audit                商品审核
├── stock        库存
│   ├── entry                入库
│   ├── out                  出库
│   ├── transfer             调拨
│   ├── distribution         配货
│   ├── delivery             发货
│   ├── search               库存查询
│   ├── change               库存变更
│   ├── synchronization      库存同步
│   └── abnormal             异常库存
├── channel      渠道
│   └── online / physical / dealer / storage   线上 / 线下 / 经销商 / 仓储
├── source       来源
│   └── area / parcel        区域 / 包裹
├── strategy     策略
│   └── autoReplenish        自动补货
├── after        售后
│   └── internal / third     内部 / 第三方
├── label        标签
│   └── hand / sys           手工 / 系统
├── user         用户
│   └── account / data / group / role
└── worksheet    工单
    └── service
center-setting 设置中心
├── tenant / file / statement / scheduler / message / user
├── base-data / menu / instance / manage / goods / indexes
common
├── dashboard / personal
```

需要特别注意的两点（都是为了不被页面名误导）：

1. **库存有 9 个子页面，但底层只有一张库存明细表**（`sstock_goods_stock_detail`：仓库 + SKU + 在库量）。说明"入库/出库/调拨/配货/发货"是**同一库存池的不同业务入口**，不是仓内作业。这再次确认第 2 节"不是 WMS"的结论。
2. **`strategy/autoReplenish`（自动补货）只有一个页面**，代码侧对应 `sstock_warehouse_warrant`（入库单）还是独立逻辑，未核实 →【未核实】。

### 5.2 代码模块

【实证，顶层目录】

```
skyer-gateway/       网关
skyer-order/         订单
skyer-goods/         商品
skyer-stock/         库存（本报告重点）
skyer-channel/       渠道（线上店铺/线下门店/经销商/销售组织）
skyer-after-sales/   售后
skyer-tags/          标签
front-end/           Vue 前端
```

库存模块内部结构：【实证，`skyer-stock/src/main/java/org/skyer/stock`】

```
api/dto | domain(entity, repository, service, service/impl) | infra | app
```

## 6. 数据模型

23 张表，按前缀分组：【实证，各 `*.sql` 文件】

**库存 `sstock_`**

```
sstock_warehouse                    仓库
sstock_storage_area                 库区（仅库区，无库位）
sstock_shop_warehouse               门店-仓库关系
sstock_goods_stock_detail           库存明细（仓库 + SKU + 在库量）
sstock_warehouse_warrant / _detail  入库单 头/行
sstock_warehouse_warrant_logistics  入库物流
sstock_outbound_delivery_order / _detail  出库发货单 头/行
sstock_adjust_record / _detail      库存调整 头/行
sstock_change_log                   库存变更流水
sstock_result_record / _detail      结果单（用途未读）
sstock_warehouse_result / _detail   仓库结果（用途未读）
```

**渠道 `channel_`**：`channel_online_shop`、`channel_physical_shop`、`channel_dealer`、`channel_sales_organization`、`channel_warehouse`

**标签 `tags_`**：`tags_label`

**流程 `sord_`**：`sord_process_define`

### 库存明细表实际字段

【实证】`skyer-stock/sql/sstock_goods_stock_detail.sql`：

```sql
`tenant_id`      bigint  COMMENT '租户Id',
`object_version_number` bigint COMMENT '版本号',
`warehouse_code` varchar(60) COMMENT '仓库code',
`sku_code`       varchar(60) COMMENT 'sku编码',
`stock_num`      decimal(12,0) COMMENT '在库库存',
```

三个可读出的结论：

1. **有 `tenant_id`** —— 多租户是表级字段，不是分库。
2. **有 `object_version_number`** —— 乐观锁字段，说明库存更新用版本号控制并发。
3. **只有"在库库存"一个数量** —— 没有 available / locked / allocated 拆分。可用量在别处算或根本没有。

## 7. 核心机制

### 7.1 可用库存服务：名字与实现不一致（W-08 已解）

`ComputeAvailableStockService` 只有一个实现方法：【实证，`ComputeAvailableStockServiceImpl.java` 全文 50 行】

```java
@Override
public void asyncEs() {
    List<GoodsStockDetail> goodsStockDetails = goodsStockDetailRepository.selectAll();
    ...
    stockEsService.createOrUpdateStockEs(dtoList, warehouseId);
}
```

即"可用库存计算"实际做的是把库存明细全量同步到 Elasticsearch。

**`ExeStockServiceImpl` 也已经读完**（`skyer-stock/.../domain/service/saga/impl/ExeStockServiceImpl.java`，全文 **47 行**），只有三个方法：

```java
public ResultRecordDTO createResultRecord(String data)
public void resultRecordAsyncEs(String data)
public OutboundDeliveryOrderDTO generateOutboundDeliveryOrder(String data)
```

没有任何可用量计算、没有任何库存扣减。

结论：**这个项目的"可用库存"只有 ES 索引这一层，没有计算引擎，也没有扣减逻辑**。所有 `availableStock` / `可用库存` 字样只出现在 DTO 与 ES 同步代码里。库存主表 `sstock_goods_stock_detail` 只有一个 `stock_num`（在库量），没有锁定量字段——结构上就做不了可用量。

这条写出来是为了避免"看到类名就以为有可用库存引擎"。

### 7.2 库存变更留痕

`sstock_change_log` + `sstock_adjust_record`：变更与调整分表，调整有头行。【实证：表存在】

### 7.3 领域事件

`AdjustSagaDTO`、`BatchRemoveDTO` 等类名显示有 Saga 相关的分布式事务编排痕迹。【实证：类名存在；Saga 实现未读 →【未核实】】

## 8. 接口与集成

- REST：64 个 Controller，按模块分布。【实证】
- 服务间：`@FeignClient` 风格（`GoodsSkuRemoteService`、`SkayerPlatformRemoteService`、`RemoteCustomizeService`）。【实证：类名】
- 搜索：库存同步 ES，供订单侧查可用量。【实证：`StockEsService`】
- 网关：`skyer-gateway`。【实证：模块】
- 无设备/PLC 对接。【实证：无相关模块】

## 9. 部署方式

- 仓库内**没有** docker-compose、Dockerfile、K8s manifest。【实证：`find` 为空】
- README 提到 k8s / nacos（GitHub topics），但部署物料不在仓库内。【自述】
- 数据库：MySQL 8（表用 `utf8mb4` + `datetime(0)` 语法）。【实证：SQL】

## 10. 活跃度

- 最后提交 2025-12-24，`pushed_at` 2025-12-24（近一年无推送）。【实证 + API】
- 开放 issue 2 个。【GitHub API】
- 提交作者为一家公司，最后一条提交只改 README。【实证】

## 11. 对我们可参考 / 不建议参考

**建议参考**

1. **`tenant_id` + `object_version_number` 的标准字段组合**：见 `sstock_goods_stock_detail.sql`。做多货主库存表时这两个字段可以直接照抄。
2. **渠道模型**：`channel_online_shop` / `channel_physical_shop` / `channel_dealer` / `channel_sales_organization` 四类渠道分开建表，比"渠道字典表 + 类型字段"更清晰。如果货主端要接多渠道，这个切分可参考。
3. **库存变更流水与调整单分离**：`sstock_change_log` 与 `sstock_adjust_record` 分开。

**不建议参考**

1. **库存粒度**：只有"仓库 + SKU + 在库量"，做不了 WMS。**不要把它当 WMS 参考**。
2. **可用库存实现**：`ComputeAvailableStockServiceImpl` 只有 ES 同步，名不副实。
3. **部署与运维**：没有部署物料，无法评估可运行性。
4. **活跃度**：近一年无代码推送。
5. **模块版本**：parent 为 `SNAPSHOT`，依赖版本不可复现。

## 12. 未核实清单

1. `skyer-parent` 中的 Spring Boot / Spring Cloud / JDK 版本 —— 父 POM 不在本仓库。
2. `sstock_result_record` / `sstock_warehouse_result` 的业务含义。
3. Saga 事务（`AdjustSagaDTO`）的编排方式与补偿逻辑。
4. 项目能否构建 —— 未执行（依赖 SNAPSHOT parent）。
5. 前端 `front-end/` 的技术栈版本 —— 未读 `package.json`。
