# CODEBUDDY.md

「货灵 WMS」代码仓库。业务源码在 `code/`，设计与规格在 `humandesign/`、`openspec/`，两边不改彼此。
开工先读 `humandesign/sysdesign/current-version.txt`：指针值即当前版本，路径拼成 `sysdesign/<当前版>/…`；指针未指向的版本只读，检索时排除。
`humandesign/调研/**/_sources/` 是第三方源码副本，不遍历、不作依据。

## 常用命令

| 命令 | 说明 |
|---|---|
| `mvn -f code/backend/pom.xml clean verify` | 后端构建与架构断言；需 JDK 21，本机默认 27，必须显式设 `JAVA_HOME`（本机 `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`） |
| `bash code/backend/scripts/check-warehouse-type.sh` | 仓型比较落点扫描 |
| `python3 .codebuddy/tools/check-design.py` | 设计文档校验，ERROR 须为 0；只查文档一致性，证明不了实现正确 |
| `python3 .codebuddy/tools/check-agent-dispatch.py` | 分派门禁校验：带命令权限的代理是否都写了「执行边界」、规则第六节是否还在；改代理定义或分派规则后必跑，ERROR 须为 0 |
| `openspec list --json` | 写操作前盘点变更；`openspec init` 仅在明确要求时执行 |

其余参数见各脚本 `--help`。

## 大局

- 权威顺序：`00` 决策 → `00a` 红线 → 主题文档；枚举、状态机、待定项与冲突裁定见 `sysdesign/<当前版>/23-权威源与引用矩阵.md`。
- 一次变更的产物与三章结构见 `openspec/config.yaml`，写作与校验规则见 `.codebuddy/rules/<规则名>/RULE.mdc`；阶段顺序与四道门禁见 `delivery-loop/RULE.mdc`。
- 主干 `main`；每个变更从最新 `main` 切 `feature/<变更名>`，一条分支只装一个变更，规格定稿与实现分两次提交。
- 技术栈以 `sysdesign/<当前版>/01-技术栈选型.md` 为准，本文不重复。
- 跨境关务与物流段不归本系统：只消费下层服务返回的结果摘要，不发起申报、不参与状态转换、不新增关务字段。
- 现有代码状态以 `code/` 实际为准，本文不记录进度。
