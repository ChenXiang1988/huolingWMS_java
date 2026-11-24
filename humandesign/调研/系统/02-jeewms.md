# 02 JeeWMS 调研报告

> 取证时间 2026-11-24。源码在 `_sources/jeewms`（364 MB，浅克隆）。
> 流程级说明（单据如何驱动下一步、状态怎么流转）见 [02a-jeewms-功能流程.md](./02a-jeewms-功能流程.md)。

## 1. 基本信息

| 项 | 值 | 来源 |
|---|---|---|
| 仓库 | https://github.com/erzhongxmu/JeeWMS （主仓在 Gitee） | — |
| 本地目录 | `_sources/jeewms` | — |
| 默认分支 | master | git |
| 最后提交 | `b548f02` 2026-10-09 `style: 集合判空改用 isEmpty()` | git log |
| Star / Fork / Watch | 154 / 56 / 6 | GitHub API |
| 主语言 | Java | GitHub API |
| 许可证 | **GNU GPL v3**（`LICENSE` 原文首行 "GNU GENERAL PUBLIC LICENSE Version 3"） | 实证 |
| 仓库创建 | 2018-11-04 | GitHub API |

## 2. 它是什么 / 不是什么

**是**：一套国内 3PL / 厂内物流场景的仓储系统，功能覆盖 WMS + OMS + BMS（计费）+ TMS，含 Web 端、Vue 前端和 uni-app PDA。

README 原文：

> "JeeWMS 以云端管理平台 + UNI-APP 智能终端为核心，构建覆盖 WMS / OMS / BMS / TMS 的仓储数字化基础能力"
> "多租户架构支持 / 公有云私有云混合云部署 / 工业物联网集成（PDA/RFID/AGV等）/ 动态计费与业务结算引擎"【自述】

**不是**：不是一个已经完成微服务化的系统。下面第 3 节是本次最重要的实证结论。

## 3. 技术栈（这一节与宣传口径不一致，逐条给证据）

仓库里有**两套代码并存**：

### 3.1 主业务：老单体（Spring 4 + Hibernate 4）

`jeewms/pom.xml` 中的属性：【实证】

```xml
<spring.version>4.0.9.RELEASE</spring.version>
<hibernate.version>4.1.0.Final</hibernate.version>
<hibernate-validator.version>4.2.0.Final</hibernate-validator.version>
<java.version>1.8</java.version>
```

这是 2014—2015 年的技术基线。`jeewms/src/main/java` 下是 jeecg 框架代码（796 个 Java 文件），业务代码在 `jeewms/jeewms-cloud/java/base-cloud-module/base-cloud-busin`（884 个 Java 文件）。

### 3.2 微服务底座：base-boot 2.4.6（业务被塞在一个模块里）

`base-cloud-busin/pom.xml` 的 parent 是 `com.base.boot:base-cloud-module:2.4.6`。【实证】

`jeewms-cloud/java` 下的模块：【实证，目录清单】

```
base-boot-base/          核心、工具、API
base-boot-starter/       rabbitmq / job(xxl-job) / lock / cloud(nacos)
base-boot-module-system/ 系统管理
base-cloud-module/
├── base-cloud-busin/    ← 全部 WMS/OMS/BMS 业务都在这里（884 个类）
├── base-cloud-gateway/
├── base-cloud-nacos/
├── base-cloud-monitor/
├── base-cloud-sentinel/
├── base-cloud-system-start/
└── base-cloud-xxljob/
```

**结论**：所谓"Spring Cloud 微服务"，实际是**一个业务模块 + 若干基础设施服务**。WMS、OMS、BMS 没有拆成独立服务，全部在 `base-cloud-busin` 一个进程里。【实证：模块清单 + 类分布统计】

### 3.3 前端

- Web：`jeewms-cloud/web/baseWeb`（Vue，含 Dockerfile、vue.config.js）。【实证】
- PDA：`jeewms-cloud/uniapp`（uni-app）。【实证】
- Vue 文件总数 1093 个。【实证】

## 4. 代码规模

| 指标 | 值 |
|---|---|
| Java 文件 | 2,463 |
| Java 行数 | 377,698 |
| Vue 文件 | 1,093 |
| Controller 总数 / 其中的业务 Controller | 349 / 117（`com/base/modules/jeewms/controller`） |
| 业务实体类 | 119（`com/base/modules/jeewms/entity`） |
| SQL 文件 | 17 个，但**均不含 WMS 建表语句**（见 6.2） |

## 5. 功能结构与代码模块

### 5.1 功能结构（PDA 端最清晰）

本项目**没有可解析的菜单数据**（菜单存在数据库，随 `.nb3` 备份、不可解析），前端页面是 jeecg 代码生成的产物（`views/wms/jee/modules` 下 182 个 `*Modal.vue` / `*Form.vue`，与实体一一对应）。**能最准确反映功能结构的是 PDA 的操作类型常量表。**

【实证】`base-cloud-busin/.../jeewms/pdaapi/CONSTANTTYPE.java`，33 种 PDA 操作类型，原文：

```
入库类：生产入库、到货审核、采购入库、半成品入库、其他入库、直拨组托、直拨组托库存
出库类：销售组托、销售出库、拆托、部门领料(出库)、生产领料(倒冲)
移库类：调拨管理、货位转储、上架、下架
拣选类：波次下架、波次分拣、复核
盘点类：明盘、暗盘、初始化
查询类：今日任务、商品库存、库存查询、商品资料、托盘清单
其他：QCFY、SAOMIAOXIANGHAOCHAXUN、QCJYJL
```

这张表把 JeeWMS 的现场作业讲全了，并纠正了一个常见误解：**它有波次**（`波次下架`、`波次分拣`），只是不在 CSV/接口层，而在 PDA 作业类型里。

另有两处更强的证据：

1. `controller/WmsPdaController.java` 第 62 行注释直接列出 PDA 的 10 类动作：【实证，原文】
   `//1.验收、2.上架、3.按单拣货、4.复核、5.波次下架、6.波次分拣、7.储位转移、8.库存查询、9.边拣边分、10.商品查询`
2. 波次有独立 Controller 与接口：【实证】`WaveToDownController`（含"波次导出列表"、"根据单号波次单打印数据"）、`WmOmQmIController`（"波次生成和波次生成到指定容器"、"下架功能-波次批量下架"），并有 `vo/WavebatchVo` 传参对象。

Web 端功能按实体域划分（【实证：119 个实体类所在包 + 前端页面命名】）：

```
基础数据 Ba*（52 个实体）      仓库/库区/库位、客户类型、费用类型、商品类型、单位、
                              承运商、城市、区域、装载方式、操作步骤、平台…
主数据 Md*（7 个实体）         商品、商品明细、客户、客户其他、供应商、托盘
入库 WmIm*                    入库通知单头/行 + WmInQmH/I 入库质检
出库 WmOm*                    出库通知单头/行 + WmOmDelvH/I 发货单 + WmOmQmI 出库质检
作业 WmTo*                    上架(WmToUp/ToUpGoods) / 下架(ToDownGoods) / 移库(ToMoveGoods)
库存 WmHisStock* / RepWmStock 库存历史（商品/库位/数据三个粒度）、库存报表
计费 jeebms / WmDayCost*      账单(BmsBillH/BmsCostDetail/BmsCostRule…) + 日费用
报表 Bi* / Wv*                可视化配置、报表
```

### 5.2 代码模块

```
jeewms/
├── src/main/java/org/jeecgframework/     # jeecg 老框架（796 类）
├── jeewms-cloud/
│   ├── java/                             # base-boot 微服务底座 + 业务
│   ├── web/baseWeb/                      # Vue 前端
│   ├── uniapp/                           # PDA 端
│   ├── database/                         # 只有一个 .nb3 备份文件
│   └── dataex/                           # 数据交换
├── DOC/
└── pom.xml                               # Spring 4.0.9
```

业务包 `com/base/modules/`：【实证，目录清单】

```
jeewms/      WMS 主体：entity(119) / controller(117) / service / mapper / pdaapi / vo
jeebms/      计费（BMS）：entity / controller / service / job
pda/         PDA 数据
approle/     应用角色
```

PDA 接口的实现方式值得注意：`pdaapi` 下只有 `ApiGetControler` / `ApiPostControler` / `CONSTANTTYPE` / `ApiEntity` / `ApiresEntity` 五个类，即**两个通用入口 + 一个类型常量分发**，不是每个业务一条 PDA 接口。【实证】

## 6. 数据模型

### 6.1 实体类（119 个，按前缀）

【实证，`_facts/jeewms-entities.txt`】前缀分布：Ba 52、Wm 39、Md 7、Or 6、Wv 4、Tm 3，其余零散。

- `Ba*`：基础数据（`BaStore` 仓库、`BaStoreArea` 库区、`BaKw` 库位、`BaArea`、`BaBin`、`BaCusSta`、`BaCost*` 费用类型…）
- `Md*`：主数据（`MdGoods` 商品、`MdGoodsItem`、`MdCus` 客户、`MdSup` 供应商、`MdBin`）
- `Wm*`：仓储单据与作业（见下）
- `Or*`：订单（`OrderItem`、`OrderFeeInfo`、`OrderPayInfo`、`OrderPackGoodsInfo`…）
- `Fx*` / `Bi*`：分销与报表配置

仓储类实体全清单：【实证】

```
WmImNoticeH / WmImNoticeI        入库通知单 头/行
WmOmNoticeH / WmOmNoticeI        出库通知单 头/行
WmOmDelvH / WmOmDelvI            发货单 头/行
WmInQmH / WmInQmI                入库质检 头/行
WmOmQmI                          出库质检 行
WmToUp / WmToUpGoods             上架任务 / 上架商品
WmToDownGoods                    下架
WmToMoveGoods                    移库
WmSttInGoods                     暂存入库
WmHisStock / WmHisStockData / WmHisStockKu   库存历史（多个粒度）
WmDayCost / WmDayCostConf / WmCostConf       日费用 / 费用配置
WmPlatIo                         平台出入库
WmTuopan / WmCartonNumber        托盘 / 箱号
WmsToShopStock / WmsBlackWhitePiece          门店库存 / 黑白件
```

命名规律：`H/I` 表示头/行，`ToUp/ToDown/ToMove` 表示作业动作，`His` 表示历史。这套命名是国内 WMS 的常见做法。

### 6.2 建表脚本：**没有**

【实证】`jeewms/database/` 只有两个文件：

```
wms20241105数据库备份.nb3        (1.5 MB, Navicat 备份格式)
WMS初始化SQL，特别谨慎使用.txt   (只有 delete 语句，不是建表)
```

17 个 `.sql` 文件全部在 `jeewms-cloud` 的框架目录里（quartz、系统菜单等），**不含 WMS 业务表 DDL**。

从那份 txt 的 delete 清单可以反证实际表名（【实证】原文）：

```
md_bin, md_cus, md_cus_other, md_goods, md_sup, wm_his_stock, wm_his_stock_ku,
wm_im_notice_h, wm_im_notice_i, wm_in_qm_h, wm_in_qm_i, wm_om_notice_h,
wm_om_notice_i, wm_om_qm_i, wm_plat_io, wm_to_up, wm_to_up_goods,
wm_to_down_goods, wm_to_move_goods, wm_stt_in_goods, wm_day_cost,
wm_cus_cost_h, wm_cus_cost_i, wm_his_stock_data, t_s_log
```

表名与实体名一致（驼峰转下划线），说明实体与物理表是一一对应的传统映射。

### 6.3 单据头字段示例

`WmImNoticeH`（入库通知单头）字段节选：【实证】

```
cusCode, imCusCode        客户编码
imData                    入库日期
imCarDri / imCarMobile / imCarNo   司机 / 手机 / 车牌
orderTypeCode / platformCode       单据类型 / 平台
imSta                     单据状态（String）
imBeizhu                  备注
sysOrgCode / sysCompanyCode        组织 / 公司
```

注意两点：

1. 状态 `imSta` 是 **String**，取值靠约定（代码中出现 `"imSta", "已完成"` 这类中文比较）。【实证：`grep` 结果】
2. 单头直接挂车辆司机字段，说明它是"带运输段的入库"场景，不是纯仓内作业。

## 7. 核心机制（可读到的部分）

1. **作业任务与单据分离**：入库通知单（`WmImNoticeH`）→ 上架任务（`WmToUp` + `WmToUpGoods`）；出库通知单（`WmOmNoticeH`）→ 下架/复核 → 发货单（`WmOmDelvH`）。这是标准的"单据—任务"两层结构。【实证：实体清单】
2. **库存历史多粒度**：`WmHisStock` / `WmHisStockKu` / `WmHisStockData` 三种历史表并存，说明同时保留了"按商品"和"按库位"两个维度的流水。【实证：实体存在；各表字段未读 →【未核实】】
3. **计费独立模块**：`jeebms` 包 + `WmDayCost` / `WmCostConf` / `WmCostType`，日费用按配置生成。【实证：类存在；计算逻辑未读】
4. **通用 PDA 网关**：`ApiGetControler` / `ApiPostControler` + `CONSTANTTYPE` 分发。【实证】

## 8. 接口与集成

- Web REST：117 个业务 Controller（`@Controller`/`@RequestMapping`）。【实证】
- PDA：2 个通用入口。【实证】
- 外部系统：README 与文档提到 SAP ECC / SAP HANA / 用友 U8 / 百胜 E3 对接。【自述，代码中未定位到对应适配类 →【未核实】】
- IoT：README 称 RFID / AGV 接入。【自述，本次未找到设备驱动包 →【未核实】】

## 9. 部署方式

- 仓库内**没有** docker-compose / K8s manifest（`find` 为空）。【实证】
- 单体部署：war/jar + 外部 Tomcat 或 Spring Boot；数据库 MySQL（`md_*` 表名与 jeecg 默认一致）。【部分自述】
- 前端单独部署（`web/baseWeb` 自带 Dockerfile）。【实证】

## 10. 活跃度

- 最后提交 2026-10-09，`pushed_at` 2026-10-09，开放 issue 3 个。【实证 + API】
- 提交信息风格为中文小改动（`style:`、`fix`），说明仍在维护但节奏平稳。【实证：最后一次提交信息】

## 11. 对我们可参考 / 不建议参考

**建议参考**

1. **国内 3PL 的字段清单**：入库单头带客户、车辆、司机、平台、单据类型 —— 见 `WmImNoticeH`。做货主端 / 三方仓时这些字段是绕不开的。
2. **"单据—任务"两层模型**：`WmImNoticeH/I` → `WmToUp/ToUpGoods`。我们做调拨时同样需要"调拨单 → 上架/下架任务"。
3. **库存历史的双维度**：`WmHisStock`（商品维度）+ `WmHisStockKu`（库位维度）。这是国内审计要求的常见落地方式。
4. **计费模块边界**：`jeebms` 独立包 + 日费用表，可以作为"计费不进 WMS 主流程"的参考。

**不建议参考**

1. **技术栈**：Spring 4.0.9 + Hibernate 4.1 + Java 8，与当前项目差距过大，任何代码都不能直接搬。
2. **状态设计**：`imSta` 用 String + 中文字面量比较，无法做状态机约束，不建议学。
3. **建表脚本**：仓库没有 DDL，只有 Navicat 备份，不能作为表结构来源。
4. **模块划分**：业务全部压在 `base-cloud-busin` 一个模块，微服务只是外壳。
5. **库存一致性**：库存是视图 `wv_stock`，由存储过程 `update_wm_his_stock(日期)` 按日刷新（`CostTask.java:251`），单据与库存无强一致保证。详见 [02a](./02a-jeewms-功能流程.md) 第 8 节。
6. **许可证**：GPL-3.0。闭源分发或 SaaS 化前的合规问题必须由法务判断，本报告不做结论。

## 12. 未核实清单

1. 多租户是否真实实现 —— README 声称，代码中未验证租户字段隔离。
2. 计费引擎的计算规则 —— 只确认类与表存在。
3. SAP / 用友 / 百胜 对接实现 —— 未在代码中定位。
4. RFID / AGV 接入代码 —— 未找到设备驱动包。
5. `.nb3` 备份的实际表结构与数据量 —— 未解析该格式。
6. 项目能否构建成功 —— 未执行 `mvn`（Spring 4 + JDK 8 组合，本机未验证）。
