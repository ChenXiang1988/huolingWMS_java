# 联调验证记录

## 结论

第 6 节 10 项联调（6.3 至 6.12）中 9 项通过、1 项部分通过（6.8 的 503 缺真实降级构造）。两轮联调共发现 20 处缺陷，修复 18 处、登记遗留 2 处。契约章与后端设计章已按实测结论修订，`check-design.py --only design` 为 `ERROR 0 / WARN 0 / INFO 0`，后端 `mvn verify` 与前端 `pnpm -r typecheck && pnpm build` 均通过。

本记录只证明接口与界面行为；不构成并发压力测试结论，也未覆盖移动作业端。

## 环境

| 项 | 值 |
|---|---|
| 日期 | 2026-11-24 至 2026-11-25 |
| 后端 | `wms-hub` 由 `mvn -f code/backend/pom.xml clean verify` 构建，独立实例 18081；短寿命令牌验证用 18082（`--wms.jwt.access-token-minutes=6`） |
| 数据库 | 本地 `wms_hub`，PostgreSQL 5433，Flyway 至 `V20261127` |
| 前端 | `apps/admin` 临时实例 5199（指向 18081）与 5198（指向 18082），`WMS_HUB_TARGET` 覆盖代理目标 |
| 浏览器 | Playwright Chromium，实机操作 |
| 账号 | 引导管理员；联调自建 `qa_recall_002`、`qa_idem_001`、`qa_idem_002`、`qa_i08_md`、`qa_i08_wn`、`qa_noperm_001`、`qa_final_001` |

未使用 8081 上的既有进程，也未抢占 5173／5174／8092。

## 逐项结果

| 任务 | 用例 | 结果 | 证据 |
|---|---|---|---|
| 6.3 | 授予权限点后同一令牌立即生效 | 通过 | `qa_recall_002` 未配权限点时 `GET /accounts` 403 `AUTHZ_2001`；给角色加 `hub.account.read` 后同一令牌立即 200 |
| 6.3 | 回收权限点后同一令牌立即生效 | 通过 | 角色权限点整体替换为空后，同一令牌立即 403 `AUTHZ_2001` |
| 6.3 | 界面表现 | 通过 | 无权限点账号登录后菜单只渲染「工作台」；直输 `/accounts` 跳 `/forbidden?permission=hub.account.read&from=/accounts`，页面显示缺失权限点、可联系「权限管理员」、返回工作台 |
| 6.4 | 账号列表空结果 | 通过 | 无可见数据时表格显示 `No Data` 且无报错 |
| 6.4 | `status` 闭集外取值 | 通过 | `GET /accounts?status=ARCHIVED` → 400 `COMMON_9001`「参数 status 取值不合法：收到 "ARCHIVED"，只接受 ENABLED、DISABLED」 |
| 6.5 | 同键同体返回同一受理号 | 通过 | 同键连发两次均 202，`acceptNo` 同为 `HUB202611250007023186`；复测 `HUB202611251035102915` |
| 6.5 | 同键异体返回 409 | 通过 | 同键换请求体 → 409 `COMMON_9005`「同一 Idempotency-Key 已受理过，本次请求体与首次不同」 |
| 6.5 | 缺幂等键 | 通过 | 缺 `Idempotency-Key` → 400 `COMMON_9001` |
| 6.5 | 并发整体替换落后方 `COMMON_9004` | 通过 | 两个 `version=6` 的 `PUT /roles/1001/permissions` 并发：一条 `SUCCEEDED`、一条 `FAILED COMMON_9004`，角色 `version` 递增到 7 |
| 6.6 | 过期令牌 401 | 通过 | 30 分钟令牌换短寿命实例：过期后 `GET /auth/me` → 401 `AUTH_1002` |
| 6.6 | 续期成功 | 通过 | `POST /auth/refresh` → 200，返回新令牌与 `expiresIn` |
| 6.6 | 刷新令牌一次性 | 通过 | 同一 cookie 第二次刷新 → 401 `AUTH_1004`；并发两次一成一败 |
| 6.6 | 到期前 5 分钟提示 | 通过 | 6 分钟令牌实例登录后约 60 秒弹出「会话即将到期」，含「稍后」「立即续期」；点续期后提示关闭、停留 `/home` |
| 6.6 | 登出后旧令牌 401 | 通过 | 登出后 `/auth/me`、`/accounts`、`/roles`、`/acceptances/**` 均 401 `AUTH_1002`；重新登录的新令牌正常 |
| 6.7 | 受理环失败回写错误码 | 通过 | 重名 `ACCOUNT_3001`、操作自己 `AUTHZ_2004`、未知权限点 `PERM_5001`、非法范围类型 `SCOPE_6002`，四张受理单 `FAILED` 且带 `errorCode` |
| 6.8 | 401 覆盖 | 通过 | 见 6.6 |
| 6.8 | 403 覆盖 | 通过 | `AUTHZ_2001`、`AUTHZ_2004` |
| 6.8 | 409 覆盖 | 通过 | `ACCOUNT_3001`、`COMMON_9004`、`COMMON_9005` |
| 6.8 | 429 覆盖 | 通过 | 同账号连续登录第 11 次 → 429 `COMMON_9002`，响应带 `Retry-After` |
| 6.8 | 503 覆盖 | 部分 | `COMMON_9003` 已由存储异常（`DataAccessException`）兜底触发，但未构造「权限或存储服务不可用」的真实降级路径 |
| 6.9 | 同键异体回归 | 通过 | 见 6.5 |
| 6.10 | 枚举闭集回归 | 通过 | `scopeType=SPECIFIED` → 400 `COMMON_9001`；`dimension` 闭集外 → 400 `SCOPE_6001`；均不落库、不出 5xx |
| 6.11 | 标识类型回归 | 通过 | `employeeId` 传 JSON 数字 → 400 `COMMON_9001`「标识必须为字符串形态」 |
| 6.12 | 响应形状对账 | 通过 | `accountId`／`accountName` 平铺；`dataScope` 五字段；`employeeId` 字符串或 `null`；导出物对应 schema 为 `string` 加 `pattern` |

## 两轮发现与处置

### 契约缺口（已在契约章补齐）

| 编号 | 问题 | 处置 |
|---|---|---|
| C-01 | `employeeId` 未标类型 | 定为员工内部主键，十进制字符串 |
| C-02 | `scopeType` 取值未写明 | 定为闭集 `ALL`／`LIST`，`dimension` 闭集 `OWNER`／`WAREHOUSE` |
| C-03 | 同键异体无对应错误码 | 新增 `COMMON_9005`（409） |
| C-04 | `AUTHZ_2002`／`AUTHZ_2003` 无触发路径 | 从 API-005、API-017 删除，保留编号给后续按域寻址接口 |
| C-05 | 标识按 JSON 整数传输，雪花号超出脚本安全整数上界 | 全部出参改十进制字符串，入参拒绝数字；实测前「停用账号」因精度丢失失败 `ACCOUNT_3003`，改后 `SUCCEEDED` |
| C-06 | 无角色账号拒绝登录未登记 | API-001 补 `AUTHZ_2001`（403）与验收用例 |
| C-07 | 重复登出语义含糊 | 明确为「首次登出后令牌入黑名单，第二次返回 401 `AUTH_1002`」 |
| C-08 | API-008 无 `version` 却要求乐观锁 | 请求体加 `version` 必填 |
| C-09 | API-010 少 `permissionCodes[]` | 补入响应 |
| C-10 | 受理单唯一约束建在指纹上，与「同键异体」矛盾 | 唯一约束改到 `idempotency_key`，指纹只用于比对 |

### 实现缺陷（已修）

| 编号 | 问题 | 落点 |
|---|---|---|
| I-01 | API-001 响应嵌套 `account` 与实现平铺不一致 | 改契约（平铺），消除与 API-005／007 的字段名冲突 |
| I-02 | API-004 `dataScope` 结构偏离 | 改契约（五字段），与 `19` 模型一致 |
| I-03 | JSON 解析失败返回 Spring 默认错误体 | 统一响应包，映射 `COMMON_9001` |
| I-04 | `version` 被丢弃，无 `COMMON_9004` | 条件更新 `where version = ?` + 应用前置比对 |
| I-05 | 同键异体被当新请求受理 | 按键索引 + 指纹比对 + 唯一约束 |
| I-06 | 刷新令牌可重复使用 | Redis `GETDEL` 原子消费 |
| I-07 | 登录限流是死配置 | Redis + Lua 原子计数，429 带 `Retry-After` |
| I-08 | 六个角色无权限点绑定 | 启动链补初始化：`05` 模块矩阵推导，只在绑定为空时补 |
| I-09 | 非法枚举落库才失败并被包装成 503 | 入口闭集校验 |
| I-10 | 路由守卫在续期分支提前放行，页面级权限判定从未执行 | 守卫重排五步，路由标 `meta.permission`，菜单与守卫同源 |
| I-11 | 账号页缺停用／重置密码／分配角色 | 三个操作补齐，全部走受理环 |
| I-12 | 角色页写接口不传 `version` | 全部补 `version`，受理完成后重拉列表刷新版本 |
| I-13 | 到期前 5 分钟提示未实现 | 按 `expiresIn` 计算到期时刻，提示可续期 |
| I-14 | `jti` 黑名单只在校验权限点的接口生效 | 上移到 JWT 过滤器，覆盖全部需鉴权接口 |
| I-15 | 角色页数据域抽屉未按 `UI-P09` 装配 | 左列角色 + 右侧两维度表单，未选节点给引导 |
| I-16 | 受理环组件用裸 `fetch` 调 API-018，不带令牌 | 改为页面注入请求函数 |

### 登记遗留（未在本变更闭合）

| 项 | 现状 | 阻塞原因 |
|---|---|---|
| 503 真实降级 | 只有存储异常兜底 | 本地无「权限服务不可用」的可控构造，压测或故障注入属独立变更 |
| 受理单幂等窗口 | 实现为按「账号 + 接口 + 键」永久去重 | 契约写 10 分钟，但受理单不物理删除，旧键退出规则未定 |
| `wms_acceptance.target_account_id` | 库内 `NOT NULL` 用 `0` 占位，边界已转 `null` | 改列需三步迁移，本轮未开 |
| API-018 查不到受理单 | 返回 `ACCOUNT_3003`（语义是账号不存在） | 缺受理单域错误码，需下一轮契约定 |
| 密码复杂度、批量条数上限、`Idempotency-Key` 长度 | 未校验 | 契约标为待确认，依赖产品策略 |
| 真实客户端 IP | 取 `getRemoteAddr()` | 前置网关的信任边界未定，生产会退化为网关单桶 |

## 复现方式

```bash
# 后端
cd code/backend
java -jar wms-hub/target/wms-hub-0.1.0-SNAPSHOT.jar --server.port=18081
# 前端（代理指向上面的实例）
cd code/web && WMS_HUB_TARGET=http://localhost:18081 pnpm --filter @wms/admin exec vite --port 5199
```

```bash
# 同键异体
curl -X POST http://localhost:18081/hub/api/accounts \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee' \
  -d '{"accountName":"qa_x","employeeId":"9501","initialPassword":"Qa@123456","roleIds":[]}'

# 标识必须字符串
curl -X POST http://localhost:18081/hub/api/accounts \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"accountName":"qa_y","employeeId":9503,"initialPassword":"Qa@123456","roleIds":[]}'
```

## 未覆盖

- 并发压力与耗时曲线：`COMMON_9004` 的结论来自双请求实测与条件更新语义，不是压测。
- 移动作业端：本变更不含安卓端。
- 审计日志页面的人工走查：接口层通过，页面按既有组件装配，未逐条点选时间区间与越域。
