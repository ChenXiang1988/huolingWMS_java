# CODEBUDDY.md This file provides guidance to CodeBuddy when working with code in this repository.

本工作区用于编写「货灵 WMS」代码，业务源码统一放在 `code/`，`humandesign/` 与 `openspec/` 只放设计与规格文档。后端骨架已落盘（父 POM、四个可启动单元、架构断言），尚无业务代码。`humandesign/调研/**/_sources/` 是被忽略的第三方源码副本，不要遍历，也不要当作依据。

## 常用命令

| 命令 | 说明 |
|---|---|
| `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -f code/backend/pom.xml clean verify` | 后端构建与架构断言；本机默认 JDK 27，父 POM 有 Java 21 门禁，必须显式指定，否则构建直接失败。 |
| `bash code/backend/scripts/check-warehouse-type.sh` | AR-10 扫描：仓型比较只允许在 strategy 包。 |
| `python3 .codebuddy/tools/check-design.py` | 设计文档校验；每次写完与三章收口前必跑，错误数须为 0。 |
| `python3 .codebuddy/tools/check-design.py <变更名>` | 只校验指定变更，相当于单独跑一次检查。 |
| `python3 .codebuddy/tools/check-design.py --init <变更名>` | 生成三章骨架；`--list-checks` 列出检查项与格式契约。 |
| `python3 .codebuddy/tools/check-design.py --json --strict` | 机器可读输出，警告也算失败；退出码 0 通过、1 失败、2 未定位对象。 |
| `python3 .codebuddy/tools/monitor-snapshot.py --check` | 基线指纹比对；`--update` 更新快照。 |
| `openspec list --json` | 写操作前盘点变更；`openspec new change "<名称>"` 建目录，禁止手工建。 |
| `openspec status --change "<名称>" --json` | 查产物完成度；`openspec instructions <产物> --change "<名称>" --json` 取写作指令。 |
| `openspec validate --specs` | 同步或合并后校验主规格；`openspec archive "<名称>"` 归档。 |

其余参数见脚本 `--help`；`openspec init` 只在用户明确要求时执行。校验脚本只查文档一致性，证明不了实现正确。

## 大局

- **版本门禁**：开工先读 `humandesign/sysdesign/current-version.txt`，指针值即当前版本目录，路径一律拼成 `sysdesign/<当前版>/…`。任何文档都不写死版本号，升级只改指针。指针未指向的版本目录冻结只读，检索时排除，回查须标注「历史版本，仅供溯源」。当前版落盘了哪几份、哪几份待迁入，以该目录 `index.md` 为准。
- **规则分工**：`sysdesign/<当前版>/` 管「为什么这样设计」，`openspec/` 管「一次变更交付什么」，`00` 管「已经定了什么」。冲突顺序 `00` → `00a` → `15` → 领域 → 落地 → 界面；枚举只在 `21`，状态与事务只在 `22`，待定项与编号对照只在 `23`。
- **变更产物**：`design.md` 按「前端设计 → 后端设计 → API 接口契约」三章写，`tasks.md` 按前端／后端／联调分组；接口契约章是前后端唯一约定源，两端不得替对方推断。子代理串行改同一文件，只替换自己章节。详见 `openspec/config.yaml` 与 `.codebuddy/rules/`。
- **目标系统**：后端是 Java 21 + Spring Boot 3 模块化单体（Maven 多模块、PostgreSQL、MyBatis-Plus、Flyway、Redis、RabbitMQ），网页端 Vue 3 + TypeScript + Vite，移动作业端 Kotlin 安卓原生；接入层／运营端／单仓端三级部署，每仓独立库，计费独立成域，四个客户端共用一套接口契约。跨境关务与物流只接收下层服务的结果摘要，不参与状态转换。技术栈细节与货主端产物形态待 v2 卷五迁入及决六拍板，勿写成既成事实。
- 规则与脚本中的「六字段」「三个可启动单元」是旧口径，以当前版的七字段、四个可启动单元为准。
