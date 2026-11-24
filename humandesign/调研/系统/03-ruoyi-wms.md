# 03 ruoyi-wms（若依 WMS）调研报告

> 取证时间 2026-11-24。源码在 `_sources/wms-ruoyi`。

## 1. 基本信息

| 项 | 值 | 来源 |
|---|---|---|
| 仓库 | https://github.com/zccbbg/wms-ruoyi | — |
| 本地目录 | `_sources/wms-ruoyi` | — |
| 默认分支 | master（README：master 同步 lite 分支） | git / README |
| 最后提交 | `ccc78f7` 2026-03-20 `docs(readme): 更新在线体验链接` | git log |
| Star / Fork / Watch | 1,243 / 310 / 11 | GitHub API |
| 主语言 | Java | GitHub API |
| 许可证 | **修改版 Apache 2.0**（附多租户与 LOGO 限制），GitHub 识别为 NOASSERTION | 实证 |
| 仓库创建 | 2022-11-06 | GitHub API |

### 1.1 许可证：README 与 LICENSE 不一致（必须看原文）

- README 顶部徽章：`License-MIT`，链接指向 `ruoyi-fast-service/blob/master/LICENSE`。【实证】
- 本仓库 `LICENSE` 文件实为："ruoyi-wms is licensed under a **modified version of the Apache License 2.0**, with the following additional conditions"。【实证，原文】

附加条件原文要点：

> "a. **Multi-tenant service**: Unless explicitly authorized by ruoyi-wms in writing, you may not use the ruoyi-wms source code to operate a multi-tenant environment."
> "b. **LOGO and copyright information**: ... you may not remove ... the LOGO or copyright information in the ruoyi-wms console or applications."
> "The interactive design of this product is protected by appearance patent."

结论：**不能简单当作 MIT 或 Apache 使用**。若做多货主 SaaS 或需要改品牌，需取得授权。是否构成对商用限制，由法务判断，本报告不给结论。【实证 + 不做法律判断】

## 2. 它是什么 / 不是什么

**是**：基于 RuoYi-Vue-Plus 血统（`ruoyi-vue-fast`）的一套轻量 WMS，包含仓库、物料、客户供应商、入库、出库、移库、盘库、库存看板、库存记录、库存明细十个功能块。

**不是**：不是多货主 3PL 系统，**也没有库位管理**。README 分支说明原文：

> "`lite` jdk17+vue3，支持多仓库，**没有库区概念**，操作简单，覆盖大部分库存应用场景。"
> "`advance` jdk17+vue3，支持多仓库、多库区，记录生产日期、过期日期、sn，可适配一物一码"

当前 master = lite。数据模型侧可验证：18 个业务实体中没有"库区""库位"表。【实证：实体清单 + `wms.sql` 表清单】

另需注意：README 功能清单第 2 条写"仓库/库区"，与 lite 分支代码不一致 —— **README 混用了 advance 分支的能力描述**。【实证：README 文本与表结构对照】

## 3. 技术栈

全部来自构建文件：【实证】

| 项 | 版本 | 位置 |
|---|---|---|
| JDK | 17 | `pom.xml` `<java.version>17` |
| Spring Boot | 3.2.6 | `<spring-boot.version>` |
| 项目版本 | 5.2.0 | `<revision>` |
| ORM | MyBatis-Plus（`ruoyi-common-mybatis`） | 模块清单 |
| 鉴权 | Sa-Token（`ruoyi-common-satoken`） | 模块清单 |
| 缓存 | Redis（`ruoyi-common-redis`） | 模块清单 |
| 多模块 | `ruoyi-admin-wms` / `ruoyi-common/*` / `ruoyi-modules/*` | 目录 |

`ruoyi-common` 下的公共模块（部分）：`sensitive`（脱敏）、`idempotent`（幂等）、`mybatis`、`oss`、`security`、`json`、`doc`、`log`、`mail`、`redis`、`web`、`encrypt`、`satoken`。【实证：pom 列表】

## 4. 代码规模

| 指标 | 值 |
|---|---|
| Java 文件 | 486 |
| Java 行数 | 40,076 |
| Controller 总数 / 业务 Controller | 55 / 16（`com.ruoyi.wms.controller`） |
| 业务实体 | 18（含 2 个基类 `BaseOrder` / `BaseOrderDetail`） |
| 业务表 | 16 张 `wms_*`，另有 19 张 `sys_*` / `gen_*` 框架表（合计 35） |

前端不在本仓库，独立仓库 `zccbbg/RuoYi-WMS-VUE`（本仓库 Vue 文件数 0）。【实证】

## 5. 功能结构与代码模块

### 5.1 功能结构

本仓库**没有菜单初始化数据**（`wms.sql` 中 `sys_menu` 插入语句为 0 条），前端在另一个仓库，因此功能结构只能从 **Controller 的 `@RequestMapping` 路径**反推。这是"接口功能"，不等于"页面功能"。【实证：16 个 Controller 的路径】

```
/wms/warehouse           仓库管理
/wms/item                物料管理
/wms/itemBrand           品牌
/wms/itemCategory        分类
/wms/itemSku             SKU
/wms/merchant            商户（含类型字段 merchantType，客户/供应商/承运商共用）
/wms/receiptOrder        入库单（头）
/wms/receiptOrderDetail  入库单（行）
/wms/shipmentOrder       出库单（头）
/wms/shipmentOrderDetail 出库单（行）
/wms/movementOrder       移库单（头）
/wms/movementOrderDetail 移库单（行）
/wms/checkOrder          盘点单（头）
/wms/checkOrderDetail    盘点单（行）
/wms/inventory           库存
/wms/inventoryHistory    库存流水
```

库存接口里可看到的两个看板维度：【实证，`InventoryController`】

```
GET /wms/inventory/boardList/item        按商品看板
GET /wms/inventory/boardList/warehouse   按仓库看板
```

### 5.2 功能结构 vs README 清单的差异（逐条对照）

README 列了 11 条功能（【自述】），与代码对照结果：

| README 功能 | 代码中的对应 | 结论 |
|---|---|---|
| 首页：库存预警与到期提醒、报表 | 未找到独立 Controller 或预警接口 | **代码中未发现** |
| 仓库/库区 | 只有 `/wms/warehouse`，无库区接口/表 | **库区不存在** |
| 物料 | `/wms/item` + 品牌/分类/SKU | 一致 |
| 客户/供应商 | `/wms/merchant`（`merchantType` 区分类型） | 一致，但是单表+类型 |
| 入库（采购/外协/退货） | `/wms/receiptOrder(+Detail)` | 单据存在；三种类型的区分未在代码中核实 →【未核实】 |
| 出库（销售/外协/调拨） | `/wms/shipmentOrder(+Detail)` | 同上 |
| 移库 | `/wms/movementOrder(+Detail)` | 一致 |
| 盘库 | `/wms/checkOrder(+Detail)` | 一致 |
| 库存看板（仓库/库区/商品三维） | 只有 item 与 warehouse 两个维度 | **少一个维度** |
| 库存记录 | `/wms/inventoryHistory` | 一致 |
| 库存明细 | `InventoryController` 仅 8 个端点：`boardList/item`、`boardList/warehouse`、`listNoPage`、`export`、`GET /{id}`、`POST`、`PUT`、`DELETE /{ids}` | **代码中未发现** |

### 5.3 代码模块

```
wms-ruoyi/
├── ruoyi-admin-wms/          # 启动 + WMS 业务（com.ruoyi.wms：controller/service/mapper/domain）
├── ruoyi-common/             # 17 个公共能力模块
├── ruoyi-modules/            # system / generator / demo
├── script/sql/wms.sql        # 建表 + 初始化数据
└── docs/                     # 截图与 GitHub 模板，无设计文档
```

业务包只有四层，无领域分层：【实证，目录清单】

```
com.ruoyi.wms
├── controller/   16 个
├── service/
├── mapper/
└── domain/
    ├── entity/   18 个实体
    ├── bo/       业务入参
    └── vo/       出参
```

## 6. 数据模型

`script/sql/wms.sql` 中 16 张 `wms_*` 表：【实证】

```
wms_warehouse                    仓库
wms_item / wms_item_sku / wms_item_brand / wms_item_category   物料 / SKU / 品牌 / 分类
wms_merchant                     商户（客户供应商合一？表名 merchant）
wms_receipt_order / _detail      入库单 头/行
wms_shipment_order / _detail     出库单 头/行
wms_movement_order / _detail     移库单 头/行
wms_check_order / _detail        盘点单 头/行
wms_inventory                    库存
wms_inventory_history            库存流水
```

**没有**：库区表、库位表、批次表、效期表、货主表、计费表。【实证：表清单】

### 单据基类字段

所有单据继承 `BaseOrder`：【实证】

```java
private Long id;
private String orderNo;
private BigDecimal totalQuantity;
private BigDecimal totalAmount;
private Integer orderStatus;   // 整数状态，无枚举
private String remark;
```

`orderStatus` 是 `Integer`，状态值含义只在 README 里用文字描述（暂存 / 作废 / 完成入库），**代码中没有枚举或状态常量类**。【实证：实体字段 + 未找到枚举类】

### 6.1 advance 分支对比（W-06 已解）

已单独克隆 `advance` 分支到 `_sources/wms-ruoyi-advance`。【实证】

| 项 | master（lite） | advance |
|---|---|---|
| 最后提交 | `ccc78f7` 2026-03-20 | `c2909bb` **2025-05-21**（更旧） |
| 业务表 | 16 张 | **18 张**（多 `wms_area`、`wms_inventory_detail`） |
| 业务实体 | 18 个 | 20 个（多 `Area`、`InventoryDetail`） |
| 库区 | 无 | **有**（`Area`：`areaCode` / `areaName` / `warehouseId`） |
| 库位 | 无 | **仍然没有** |
| 批次/效期 | 无 | **有**，但只放在库存明细行上 |

`InventoryDetail`（`wms_inventory_detail`）字段全文：【实证】

```
receiptOrderId, receiptOrderType, orderNo, type,
skuId, warehouseId, areaId,
quantity, remainQuantity,
batchNo, productionDate, expirationDate,
amount, remark
```

三点结论：

1. **advance = 库区 + 批次效期**，粒度是"仓库 + 库区 + SKU + 批次"，`areaId` 是库区，不是库位。
2. **没有独立批次表**：批号、生产日期、效期是库存明细上的三个字段，没有批次主数据、没有批次库存汇总表。
3. **没有 SN 字段**：README 说 advance"记录生产日期、过期日期、sn，可适配一物一码"，但 `InventoryDetail` 与 `ItemSku` 里都没有 `sn` 字段 →【未核实，代码中未找到】。

所以：**若需要库位级管理，这个项目两个分支都不能用**；若只需要库区 + 批次效期，advance 分支可用，但要注意它比 master 旧一年多。

## 7. 核心机制

1. **单据 + 明细两层，无独立任务层**：入库单直接操作库存，没有"上架任务"实体。移库、盘点同理。【实证：实体清单】
2. **库存直接增减 + 流水表**：`wms_inventory` 存当前量，`wms_inventory_history` 记流水。

   **库存增减没有任何并发控制**（W-18 已解）：`InventoryService` 是 `class ... extends ServiceImpl`，`add()` / `subtract()` 都标了 `@Transactional`，但实现是典型的 read-modify-write：【实证 `InventoryService.java:166-225`】

   ```java
   Inventory result = inventoryMapper.selectOne(wrapper);   // 读
   BigDecimal after = before.add(qty);                      // Java 里算
   result.setQuantity(after);
   updateBatchById(updateList);                             // 整行覆盖写
   ```

   ```java
   // 扣减
   BigDecimal afterQuantity = beforeQuantity.subtract(qty);
   if (afterQuantity.signum() == -1) throw new ServiceException("库存不足", ...);
   result.setQuantity(afterQuantity);
   ```

   `grep lock / Lock / synchronized` 在该类中**零命中**，也没有 `setSql("quantity = quantity + ?")` 这类原子更新。两个并发出库单读同一行库存时会互相覆盖（丢更新），而"库存不足"的校验也拦不住（两边都读到够的数量）。这是本项目最严重的实现缺陷。
3. **打印机集成**：README 称支持 Lodop 与网页打印。【自述】

## 8. 接口与集成

- REST：16 个业务 Controller，与 16 张单据/主数据表一一对应。【实证】
- 无消息队列、无外部系统适配层（未发现 `mq`/`erp`/`sap` 相关包）。【实证：依赖与目录】
- 打印：Lodop（前端能力，本次未验证）。【自述】

## 9. 部署方式

- 有 `ruoyi-admin-wms/Dockerfile`，**没有** docker-compose / K8s 清单。【实证】
- 依赖 MySQL + Redis + 文件存储（OSS 模块）。【实证：依赖模块】
- 数据库初始化：`script/sql/wms.sql` 一个文件搞定。【实证】

## 10. 活跃度

- 最后提交 2026-03-20（约 8 个月前），`pushed_at` 2026-03-20，开放 issue 4 个。【实证 + API】
- 提交主题多为 README 与体验地址更新，属低频维护。【实证：最后一条提交信息】

## 11. 对我们可参考 / 不建议参考

**建议参考**

1. **最小可用的表结构**：17 张表覆盖"仓库-物料-出入库-移库-盘点-库存-流水"，适合作为**新项目起点**而不是成熟方案。见 `script/sql/wms.sql`。
2. **单据头/行 + 基类的写法**：`BaseOrder` 抽公共字段，头行分离。简单直接。
3. **模块化公共能力清单**：`ruoyi-common` 下 17 个模块（幂等、脱敏、加密、OSS、Sa-Token）是现成的横切能力清单，可对照自查我们缺什么。

**不建议参考**

1. **状态设计**：`Integer orderStatus` 无枚举，状态流转靠 Service 里判断，无法约束非法流转。
2. **无任务层**：单据直接改库存。如果要做"波次/任务/设备下发"，这套模型接不上。
3. **无库位/批次**：lite 分支没有库区和库位概念，做精细化仓储必须换 advance 分支或自己补。
4. **许可证**：修改版 Apache + 多租户限制 + LOGO 限制 + 外观专利声明，商用前必须确认。
5. **README 与代码不一致**：README 说"仓库/库区"，lite 代码里没有库区，读文档时要对照分支。

## 12. 未核实清单

1. `advance` 分支中 SN 字段是否存在（README 声称支持一物一码，代码中未找到）。
2. 打印（Lodop）的前端实现 —— 前端在另一个仓库。
3. 与 RuoYi 上游的差异量 —— 未与 `ruoyi-vue-fast` 对比。
4. 在线 Demo 是否与 master 代码一致 —— 未访问。
5. 并发丢更新在多少 QPS 下会实际发生 —— 未压测。
