# Tasks

依据：`proposal.md`、`specs/change-scope-check/spec.md` 与本变更 `design.md`「后端设计 · 工具改动方案」。

## 一、前端

本次无前端任务。不改页面、路由、组件与样式令牌。

## 二、后端

本次无后端任务。**本次无表结构变更**，不建表、不写迁移脚本、不改运行时路径。

## 三、前后端联调

本次无联调任务。本次不改接口，契约文件不得产生差异。

## 四、工具与门禁

- [ ] 4.1 在 `check-design.py` 的上下文构造阶段加入窄范围判定：契约章无 `### API-xxx` 条目、契约章含「本次无接口变更」、后端章含「本次无表结构变更」，三条同时成立才为真；判定结果存进上下文对象。
- [ ] 4.2 在检查执行循环中，判定为真时跳过 `API-01`、`BE-03`、`BE-04`、`BE-05`、`BE-06`、`BE-07`、`FE-03`、`FE-04`、`FE-05`，并逐项输出 INFO 说明豁免依据；清单外的检查一律照常执行。
- [ ] 4.3 `--list-checks` 输出标注上述九项为「窄范围变更可豁免」，不改变检查编号与分组。
- [ ] 4.4 正例：`python3 .codebuddy/tools/check-design.py fix-check-design-scope`，ERROR 为 0，输出含九项豁免 INFO。
- [ ] 4.5 正例：`python3 .codebuddy/tools/check-design.py refine-admin-shell-ui`，ERROR 为 0。
- [ ] 4.6 回归：`python3 .codebuddy/tools/check-design.py add-three-end-product-archive`，输出与改造前逐项一致，不新增豁免 INFO、不减少 ERROR。
- [ ] 4.7 反例：临时删掉本变更的「本次无接口变更」声明，确认恢复报 `API-01`；恢复声明后再跑一次确认回到 0。临时改动不得提交。
- [ ] 4.8 反例：在临时副本的契约章加一条接口条目，确认不认定为窄范围、接口检查照常执行。临时文件用完删除。
- [ ] 4.9 在 `.codebuddy/references/校验指南.md` 补一节「窄范围变更豁免」，写清三条判定、九项豁免、不可豁免清单与 INFO 留痕要求。
- [ ] 4.10 跑 `python3 .codebuddy/tools/check-agent-dispatch.py`，ERROR 为 0。
