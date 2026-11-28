# 校验脚本窄范围豁免 · 实施验证记录

> 变更名：`fix-check-design-scope`。本文件逐项记录实测命令、结果与证据。所有命令由主代理执行并复核，不采信子代理自述。

## 1 交付形态与本次改动

改动对象是设计文档校验器与一份参考资料，不涉及后端、前端、契约与数据库。

| 文件 | 改动 | 承接 |
|---|---|---|
| `.codebuddy/tools/check-design.py` | 新增模块级常量 `SCOPE_EXEMPT_CODES` 与判定依据文案；`Ctx` 增加 `scope_exempt`；新增 `detect_scope_exempt`；执行循环按清单跳过并逐项 INFO；`print_list_checks` 标注豁免项；`check_frontend_features` 内只跳过 `FE-03`~`FE-05`，保留 `FE-06` | `backend-impl` |
| `.codebuddy/references/校验指南.md` | 「结果处置」节下新增「窄范围变更豁免」小节，写清三条判定、九项豁免、不豁免清单与使用纪律 | 主代理（参考资料，非代码） |
| `openspec/changes/refine-admin-shell-ui/design.md` | 补两句显式声明，使该变更满足豁免条件 | 主代理（文档） |

判定条件三条，缺一不豁免：契约章无任何 `### API-xxx` 条目、契约章含「本次无接口变更」、后端章含「本次无表结构变更」。

## 2 实测命令与结果

| # | 命令 | 期望 | 实际 |
|---|---|---|---|
| 1 | `python3 -m py_compile .codebuddy/tools/check-design.py` | 无语法错误 | 通过 |
| 2 | `python3 .codebuddy/tools/check-design.py --list-checks` | 五组带「窄范围变更可豁免」标注，其余不变 | 通过；`API-01..03`、`BE-03`、`BE-04..05`、`BE-06`、`BE-07..09` 均带标注，`API-04` 起未标注 |
| 3 | `python3 .codebuddy/tools/check-design.py fix-check-design-scope` | 正例：ERROR 0，输出九项豁免 INFO | 通过；`ERROR 0 WARN 2 INFO 10`，含 `SCOPE` 汇总 1 条与九项逐条豁免 |
| 4 | `python3 .codebuddy/tools/check-design.py refine-admin-shell-ui` | 正例回归：ERROR 0 | 通过；`ERROR 0 WARN 2 INFO 7` |
| 5 | `python3 .codebuddy/tools/check-design.py add-three-end-product-archive` | 反例一：有接口的变更行为不变 | 通过；`ERROR 0 WARN 0 INFO 0`，输出接口清单与改造前一致，无豁免 INFO |
| 6 | 删除副本中的「本次无接口变更」后 `--design /tmp/cds/design.md` | 反例二：恢复报 `API-01` | 通过；`ERROR 7`，`API-01` 重现 |
| 7 | 副本中加一条 `### API-001 测试接口` 后 `--design /tmp/cds/design2.md` | 反例三：不判窄范围，接口检查照常 | 通过；无 `SCOPE` INFO，`API-05` 照常报缺字段 |

反例用副本放在 `/tmp/cds`，验证后已删除，仓库内文档未被临时改动污染。

## 3 全量门禁

| 门禁 | 命令 | 结果 |
|---|---|---|
| 后端构建与架构断言 | `JAVA_HOME=<jdk21> mvn -f code/backend/pom.xml clean verify` | 通过，退出码 0；Flyway 校验 3 个迁移，ArchUnit 正常 |
| 前端类型检查与构建 | `cd code/web && pnpm typecheck && pnpm build` | 通过；四个包全部 Done，admin 构建 2.60s |
| 设计文档校验 | `python3 .codebuddy/tools/check-design.py <变更名>` | 两个变更均 ERROR 0 |
| 分派门禁 | `python3 .codebuddy/tools/check-agent-dispatch.py` | `ERROR 0 WARN 0` |

## 4 遗留 WARN 与理由

| 项 | 理由 |
|---|---|
| `BE-12`（未见 Flyway） | 窄范围变更无迁移脚本可写，属结构性不适用 |
| `FE-07`（无页面接口映射表） | 本次无接口可映射，加映射表会引入不存在的编号 |
| `X-02`（错误码自动对账跳过） | 两次变更都未采用常量式错误码，脚本按设计降级为人工核对 |
| `API-14` 等已消除 | 补「调用方处置」列后不再告警 |

上述 WARN 均不涉及文档缺失，不改脚本迁就。

## 5 风险与边界

1. **解析盲区**：条件一只看 `### API-xxx` 条目。若某变更把接口写成纯项目符号，`extract_api_entries` 认不出，叠加两句声明会被误判为窄范围。当前靠「声明必须如实」这条纪律兜底，后续如出现此类写法，应补条目识别而非放宽声明。
2. **子串匹配**：两句声明按纯子串判定，写成「并非本次无接口变更」也会命中。已知，未加反向词排除，避免规则复杂化。
3. **豁免不影响退出码**：仍有 ERROR 时退出码照旧为 1，`--strict` 下 WARN 也照旧失败。

## 6 结论

脚本改造与四处回归全部实测通过，全量门禁四项全绿，可归档。豁免只作用于九个结构性检查，有接口的变更行为不变（反例一与 `add-three-end-product-archive` 输出逐项一致）。
