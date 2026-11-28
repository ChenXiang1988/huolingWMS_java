# 三端商品档案 · 实施验证记录

> 变更名：`add-three-end-product-archive`。分支：`feature/add-three-end-product-archive`。
> 本文件逐项记录实测命令、结果、用例数与证据，供归档确认。所有命令由主代理执行或复核，不采信子代理自述。

## 1 交付形态与本次改动

本次开工时 `main` 已存在前序实施提交（`3be07f1` 后端与领域、`a24753a` 三端 Web 与契约导出、`b6bcee7` 集成测试门禁与翻页数据隔离等），任务清单当时仍为 0/42 未勾选、构建红。本次实施完成三件事：

1. 修复两处阻断构建的 AR-02 分层违规：Hub 的下行事件端口 `ArchiveEventTransport` 从 `application` 迁到 `domain`；Site 新增 `domain/DownlinkEventConsumer` 端口，让 `SkuSnapshotConsumer` 实现它、`RabbitDownlinkListener` 只依赖 domain 端口。由 `backend-impl` 落盘。
2. 修正一条与权威设计冲突的 AR-09 断言：删除 `ModuleBoundaryRulesTest` 中「主数据模块不暴露领域实体」条目（该条擅自把 `masterdata` 加入 AR-09 清单，与 `v2/14` 的 10 模块清单、`v2/03` 的「domain 可依赖 M01 值对象」、本次 design.md 的「Hub 与 Site 只调用 Kernel 规则」三处权威结论冲突）。由 `test-author` 落盘。
3. 删除端口迁移后残留、已无引用的旧接口文件 `application/ArchiveEventTransport.java`（破坏性操作，按分派规则第六节由主代理执行）。

改动文件：

| 文件 | 改动 |
|---|---|
| `wms-hub/.../archive/domain/ArchiveEventTransport.java` | 新增端口接口（由 application 迁入） |
| `wms-hub/.../archive/infrastructure/RabbitArchiveEventTransport.java` | 改 import 指向 domain 端口 |
| `wms-hub/.../archive/application/ArchiveOutboxDispatcher.java` | 改 import 指向 domain 端口 |
| `wms-hub/.../archive/application/ArchiveEventTransport.java` | 删除残留旧接口 |
| `wms-hub/.../archive/application/ArchiveOutboxDispatcherTest.java` | 补一行 import（同包解析改为显式指向 domain 端口） |
| `wms-site/.../site/domain/DownlinkEventConsumer.java` | 新增下行消费端口 |
| `wms-site/.../site/application/SkuSnapshotConsumer.java` | 实现 domain 端口，`consume` 加 `@Override` |
| `wms-site/.../site/infrastructure/RabbitDownlinkListener.java` | 依赖改为 domain 端口 |
| `wms-archtest/.../ModuleBoundaryRulesTest.java` | 删除错误断言，类注释改为与权威 10 模块清单一致 |

对外契约、迁移脚本、前端页面、设计文档本次未改；契约与字段口径已由前序提交落地，本轮复核确认。

## 2 实测命令与结果

| # | 命令 | 结果 | 证据 |
|---|---|---|---|
| 1 | `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -f code/backend/pom.xml clean verify` | BUILD SUCCESS，Reactor 8/8 SUCCESS | Reactor Summary 八模块全 SUCCESS；`wms-archtest` 架构断言 22 例全绿 |
| 2 | `pnpm typecheck`（在 `code/web`） | 通过，6/7 workspace 项目类型检查 Done | `apps/admin`、`apps/owner`、`apps/site` 均 `vue-tsc --noEmit` Done |
| 3 | `pnpm build`（在 `code/web`） | 通过，三端均 build 成功 | 三端 `dist/assets` 产物齐全，无构建错误（仅 chunk 体积提示） |
| 4 | `python3 .codebuddy/tools/check-design.py` | ERROR 0 / WARN 0 / INFO 0 | 输出「无问题」；design.md 列出 API-001 至 API-020 无问题 |
| 5 | `python3 .codebuddy/tools/check-agent-dispatch.py` | ERROR 0 / WARN 0 | 带命令权限 5 个代理执行边界齐全；DISPATCH-03 五要素齐全 |
| 6 | `bash code/backend/scripts/check-warehouse-type.sh` | AR-10 通过 | 输出「AR-10 通过」 |

## 3 测实用例统计（从 surefire / failsafe 报告逐个文件求和）

| 模块 | 单元测试 | 集成测试 | 失败/错误 | 跳过 |
|---|---:|---:|---:|---:|
| `wms-kernel` | 54 | 0 | 0 | 0 |
| `wms-hub` | 78 | 55 | 0 | 0 |
| `wms-site` | 19 | 26 | 0 | 0 |
| `wms-archtest` | 22 | 0 | 0 | 0 |
| **合计** | **173** | **81** | **0** | **0** |

架构断言：`LayeringRulesTest` 8 例、`UnitIsolationRulesTest` 4 例、`ModuleBoundaryRulesTest` 10 例，全绿。集成测试直连本地 PostgreSQL（容器 `huoling-wms-local-postgres-1`，5433→5432），未使用内存库、未跳过任何 `*IT`。

## 4 关键验收路径与证据

| 验收项 | 证据 |
|---|---|
| 五态状态机合法/非法迁移 | `SkuArchiveStateMachineTest` 20 例，含非法迁移反例 |
| 货主内编码/条码判重 | `SkuArchiveDedupRuleTest` 7 例；`SkuArchiveConstraintIT` 10 例（DB 唯一约束） |
| 版本乐观锁与批量逐条结果 | `SkuArchiveVersionRuleTest` 7 例；`SkuReviewBatchIT` 6 例 |
| 幂等十次并发、同键异体 | `ArchiveIdempotencyRaceIT` 1 例；`ArchiveIdempotencyIT` 7 例 |
| 数据域越权（货主跨货主、单仓跨仓） | `ArchiveDataScopeIT` 6 例；`SiteArchiveApiIT` 12 例 |
| 键集分页与 sortBy/sortDir | `SkuArchivePagingIT` 7 例；`SkuSnapshotPagingIT` 8 例 |
| 契约错误码与 HTTP 状态 | `ArchiveContractHttpIT` 11 例；`ArchiveHttpTest` 2 例 |
| 变更申请发起、审核与下行 | `ChangeRequestFlowIT` 7 例；`SkuSnapshotDownlinkIT` 6 例；`SkuSnapshotConsumerTest` 5 例 |
| 受理超时/拒绝补偿、错误码双形态 | `ArchiveAcceptanceCompensator`（超时扫描）与前端 `packages/request/src/index.ts`（`"0"` 与 `0`、`1002` 双形态）实现落点已抽查；`SiteAcceptanceServiceTest` 5 例 |
| 三端页面交付 | `apps/admin`、`apps/owner`、`apps/site` 路由与页面齐全，`pnpm typecheck` 与 `pnpm build` 全通过 |

## 5 尚未处理的观察项（不阻断归档，登记备查）

测试实现者在核对时报告三项前瞻性观察，均非本次变更引入、现状 0 处违规、不影响构建与验收，未扩大本次改动范围：

1. `v2/14-构建约束与自动检查.md` 第 181 行写「十一个模块各写一条」，与其后第 190-191 行的 10 项清单自相矛盾；本次按 10 项权威清单修正了测试，文档文字瑕疵留给文档责任人。
2. AR-09 修正后余下 10 条断言的目标包（location、stock、task 等）在当前代码库尚未建立，断言暂「空转」；真正的聚合根在 `masterdata.archive.domain` 与 `masterdata.identity.domain`，当前无任何断言覆盖跨单元引用它们。现状引用为 0，如需补断言应另立变更并同步 03/14。
3. AR-03 的 hub 作用域写 `..wms.hub..`，而 hub 业务代码落在 `com.huoling.wms.masterdata.archive/identity`，该断言实质只覆盖启动类；AR-07（`@ValueObject` 零使用）与 AR-08（无 `DomainEventPublisher`、事件实际走 outbox）同样空转。均属设计断言与代码布局的存量错配，不属本次需求范围。

## 6 结论

四类产物齐全、`check-design.py` 零错误；后端全量构建与集成测试、前端类型检查与构建、设计校验、分派门禁、仓型扫描五项门禁全绿；`tasks.md` 42 项全部勾选。本变更具备归档条件，待用户确认本记录结论后同步正式规格并归档。
