# 技术落地

领域内核不依赖框架；表按模块独占、约束优先于应用校验；事务边界默认到聚合根，唯一例外是「任务完成 + 库存扣减」必须同事务；库存行用乐观锁、库位行用行锁，加锁顺序固定。

---

## 问题

**解决**：定住包结构、表结构与命名、核心表的关键列与约束、事务边界的边界、并发控制的三档手段、事件投递方式、迁移规范。

**不解决**：业务规则本身（见 04、06、08、17）、模块怎么切（见 03）、部署拓扑（见 02）、具体 SQL 的全量清单。

---

## 设计

### 包结构：模块在前、四层在后

```
com.huoling.wms.<module>
  domain          实体、值对象、仓储接口、领域服务（不依赖框架）
  application     命令、查询、应用服务（事务边界在这层）
  infrastructure  持久化实现、外部调用、事件投递
  api             Controller、请求响应对象、错误码映射
```

`<module>` 取 03 的十个模块名（`warehouse`、`location`、`stock`、`task`、`order`、`strategy`、`processing`、`billingfact`、`device`）。

依赖规则由 ArchUnit 断言（见 14），不靠人看。

### 表按模块独占，前缀区分两个域

| 前缀 | 归属 | 例子 |
|---|---|---|
| `wms_` | 仓储作业与仓网运营（同一个库） | `wms_stock`、`wms_task`、`wms_order_head` |
| `bill_` | 计费域（独立库） | `bill_contract`、`bill_fact`、`bill_detail` |

两个库物理隔离（D49），不存在跨库 join。计费域需要 WMS 的信息时，靠事实事件携带，不反查。

### 主键用雪花 ID，不用自增

| 项 | 选型 | 理由 |
|---|---|---|
| 主键 | 雪花 ID（`bigint`） | 多 Site 各自生成不冲突；单号与主键分离 |
| 业务单号 | 类型前缀 + 仓编码 + 日期 + 序号 | 与主键无关，可读、可打印 |
| 排序 | 按主键或业务时间，不按创建自增 | 自增在有并发写入时不可靠 |

### 公共列只有五组，不加审计泛滥字段

| 列 | 用途 |
|---|---|
| `created_at` / `created_by` | 创建留痕 |
| `updated_at` / `updated_by` | 最后修改留痕 |
| `version` | 乐观锁，`bigint`，每次更新 +1 |

**不设 `deleted_at`**：数据不物理删除（INV-18），主数据用状态位（启用/停用），单据用五态（含已取消），流水不可删。用一个统一的软删列反而会让"删除"变成一种隐式状态，与五态打架。

### 库存表：七字段唯一 + 两个数量 + 校验约束

```sql
create table wms_stock (
  id            bigint       primary key,
  owner_id      bigint       not null,
  location_id   bigint       not null references wms_location(id),
  container_id  varchar(32)  not null default '',
  sku_id        bigint       not null,
  batch_no      varchar(64)  not null default '',
  stock_status  varchar(16)  not null,
  serial_no     varchar(64)  not null default '',
  qty_on_hand   numeric(18,3) not null default 0,
  qty_allocated numeric(18,3) not null default 0,
  version       bigint       not null default 0,
  created_at    timestamptz  not null,
  updated_at    timestamptz  not null,
  constraint uk_wms_stock unique (owner_id, location_id, container_id, sku_id, batch_no, stock_status, serial_no),
  constraint ck_wms_stock_qty check (qty_on_hand >= 0 and qty_allocated >= 0 and qty_allocated <= qty_on_hand)
);

create index idx_wms_stock_loc on wms_stock (location_id, stock_status);
create index idx_wms_stock_sku on wms_stock (owner_id, sku_id, stock_status);
```

三个要点：

1. `container_id` 与 `serial_no` 默认空串而非 NULL：唯一索引里 NULL 互不相等，留空会产生重复库存行。
2. `ck_wms_stock_qty` 让「负库存」和「分配超在库」在数据库层就写不进去，应用层校验只是提前报错。
3. 两个索引分别服务「按库位查库存」与「按 SKU 查可用量」，覆盖绝大多数查询。

### 库存流水表：只追加，无 UPDATE 权限

```sql
create table wms_stock_txn (
  id            bigint       primary key,
  txn_no        varchar(48)  not null,
  owner_id      bigint       not null,
  location_id   bigint       not null,
  container_id  varchar(32)  not null default '',
  sku_id        bigint       not null,
  batch_no      varchar(64)  not null default '',
  stock_status  varchar(16)  not null,
  serial_no     varchar(64)  not null default '',
  change_type   varchar(24)  not null,   -- 上架/下架/冻结/解冻/移库/调整
  qty_before    numeric(18,3) not null,
  qty_after     numeric(18,3) not null,
  change_qty    numeric(18,3) not null,
  biz_type      varchar(24)  not null,   -- 任务/单据/调整单
  biz_no        varchar(48)  not null,
  operator_id   bigint,
  device_id     bigint,
  occurred_at   timestamptz  not null
);

revoke update, delete on wms_stock_txn from wms_app;
```

`qty_before` / `qty_after` 必须存，不能只存变动量：对账时要能从任意一条流水重放出当时余额。

### 事务边界：默认到聚合根，一个例外

| 场景 | 事务范围 |
|---|---|
| 单个聚合的状态流转 | 该聚合一行（加乐观锁） |
| 库存变动 + 流水写入 | 同一事务：锁库存行、改数量、插流水 |
| **任务完成 + 库存扣减** | **同一事务**（唯一例外） |
| 单据状态推进 | 由任务事件驱动，异步，不在任务事务内 |
| 计费事实投递 | 本地消息表，同事务写入，异步投递 |

例外的理由：02 要求库存强一致。任务完成与库存扣减分属两个聚合，如果拆开用事件，就会出现「任务已完成、库存没扣」或反之。两者在同一库内，用同一个本地事务是最简单的强一致手段。

实现方式：任务的应用服务在同一事务内调用库存的应用服务（不是调仓储、不是发事件）。

### 并发控制三档，按冲突强度选

| 场景 | 手段 | 理由 |
|---|---|---|
| 库存行数量变更 | **乐观锁** `version` + 重试 3 次 | 冲突集中度低（不同 SKU、不同库位互不相干），悲观锁会拉长事务 |
| 库位容量核算与上架选址 | **行锁** `SELECT ... FOR UPDATE` | 需要汇总该库位多行库存求占用，读多行必须锁住整个库位 |
| 任务抢单 / 单号生成 | **Redis 原子操作** | 高频、短期、可重试，不占数据库连接 |
| 单据状态推进 | 乐观锁 | 同一单据并发概率低 |

**加锁顺序固定：先库位、后库存。** 上架场景两把锁都要，顺序不一致会死锁。这条写进代码规范，由评审与测试覆盖多线程用例。

库存乐观锁的伪代码：

```java
// 重试在外层，聚合内只做一次尝试
for (int attempt = 0; attempt < 3; attempt++) {
    Stock stock = repo.findByKey(key);
    stock.subtract(qty);                 // 聚合内校验不变量
    if (repo.updateWithVersion(stock)) { // where version = #{oldVersion}
        return;
    }
}
throw new ConcurrentModificationException("库存并发冲突，请重试");
```

### 事件走本地消息表，不走进程内总线

| 步骤 | 做法 |
|---|---|
| 写事件 | 与业务同一事务写 `wms_outbox`（事件 ID、类型、载荷、状态） |
| 投递 | 定时任务扫 `status=PENDING` 的记录，发 RabbitMQ |
| 消费 | 按事件 ID 幂等去重 |
| 补偿 | 投递失败重试，超过阈值告警，人工介入 |

不用进程内事件总线做跨模块通信：多副本部署时只有处理请求的那个副本会收到事件（13 里记过这条反例）。

### 分页用键集，不用 offset

列表查询一律 `where id > #{lastId} order by id limit #{size}`。

理由：单据与流水表会到千万级，`offset 100000` 会全表扫描前半段。管理后台的"跳页"需求用"跳到第 N 页"改为"按时间或单号定位"。

### 批量写入限 500 行一批

批量插入或更新按 500 行切分，避免单条 SQL 过长与锁范围过大。

### 迁移脚本只增不改

| 规则 | 内容 |
|---|---|
| 工具 | Flyway，脚本放 `db/migration` |
| 命名 | `V<时间戳>__<模块>_<描述>.sql` |
| 只增 | 已合入主干的脚本不可修改，修正靠新脚本 |
| 大表变更 | 加字段优先可空；改类型分「加新列 → 回填 → 切读 → 删旧列」四步 |
| 索引 | 大表建索引用 `CONCURRENTLY`（PostgreSQL），避免锁表 |

### 表清单按模块归属

| 模块 | 主要表 |
|---|---|
| 仓网（M10） | `wms_warehouse`、`wms_warehouse_capability`、`wms_warehouse_calendar` |
| 库位（M02） | `wms_area`、`wms_location`、`wms_container` |
| 库存（M03） | `wms_stock`、`wms_stock_txn`、`wms_stock_abnormal` |
| 任务（M04） | `wms_task_group`、`wms_task_execution`、`wms_task_lock` |
| 单据（M05） | `wms_order_head`、`wms_order_line`、`wms_order_line_ext_*` |
| 策略（M06） | `wms_strategy`、`wms_strategy_param` |
| 加工（M07） | `wms_process_order`、`wms_process_line`、`wms_bom_single` |
| 作业事实（M08） | `wms_outbox`、`wms_fact` |
| 设备（M09） | `wms_device`、`wms_device_binding` |
| 主数据（M01） | `wms_owner`、`wms_sku`、`wms_batch_attr`、`wms_staff` |

计费域的表在 `bill_` 前缀下，见 08 的五组划分。

---

## 约束与验证

| 约束 | 强制手段 |
|---|---|
| domain 不依赖框架 | 构建期：ArchUnit AR-02（见 14） |
| 库存唯一键与数量约束 | 数据库层：`uk_wms_stock` 与 `ck_wms_stock_qty`；核对层：日结校验负值与超分配 |
| 流水不可改 | 数据库层：`revoke update, delete`；核对层：流水条数与业务单据数比对 |
| 事务不跨聚合（除例外） | 代码评审 + 测试：跨聚合的同步调用只允许出现在「任务完成 + 库存扣减」一处，其余走事件 |
| 加锁顺序固定 | 代码层：统一由库存服务封装加锁方法，外部不得自行加锁；测试覆盖并发上架用例 |
| 事件必落表 | 应用层：发布事件只能走 outbox 接口；核对层：事件表与投递记录对账 |
| 分页不用 offset | 构建期：CI 扫描 `offset` 关键字出现在分页查询中即告警 |
| 迁移只增不改 | 构建期：Flyway 校验 checksum，已合入脚本改动即失败 |
| 每张表只属一个模块 | 代码层：ArchUnit 禁止跨模块 mapper 引用（AR-09） |

引用的决策编号：D09、D11、D49、D51；不变量：INV-08、INV-09、INV-18；库存模型见 04，任务与锁定见 06，计费域表见 08，模块与依赖见 03。

本文只定结构与机制。具体某张表的完整列清单随需求迭代补，不在设计文档里穷举。
