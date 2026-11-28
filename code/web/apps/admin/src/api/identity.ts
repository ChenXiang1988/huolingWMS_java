/**
 * 身份与权限接口的响应形状，只此一份。
 * 字段逐项对应设计章的契约条目：API-005/007（账号）、API-009（分配角色）、
 * API-010（角色）、API-014（权限点）、API-015（数据域）、API-018（受理结果）；
 * 账号条目与开通请求体的「端」与归属货主字段，取变更 `add-owner-archive` 契约 1.7 的字段扩展
 * （`client`、`ownerId`、`ownerNo`、`ownerName`），本文件不另起同义字段。
 * 页面只引用本文件，不各写一份请求体或响应体。
 *
 * 标识口径按契约「标识、类型与枚举约定」：`id`、`accountId`、`employeeId`、
 * `roleIds[]`、`targetIds[]` 出入参一律为十进制字符串。主键是雪花号
 * （如 `2125394920742858753`），超出 JavaScript 安全整数上界 `9007199254740991`，
 * 按 JSON 数字传输会在浏览器侧静默丢精度。前端只原样透传字符串，不做
 * `Number()`／`parseInt` 转换，也不做数值比较；`version` 仍是 JSON 整数。
 */
import type { AcceptanceFailureView } from '@wms/design'
import { describeErrorCode, type HttpClient } from '@wms/request'

/**
 * 标识的格式：契约给出的 `pattern` `^[1-9][0-9]*$`。
 * 前端只用它做格式校验，取值始终保留字符串形态，不转成数字。
 */
export const identifierPattern = /^[1-9][0-9]*$/

/** 写接口统一返回，见 API-006/008/009/011/012/013/016 的 202 响应。 */
export interface AcceptTicket {
  acceptNo: string
}

/**
 * API-001／API-002 的响应：身份字段平铺，不用嵌套 `account`。
 * `expiresIn` 是整数秒，`12` 的 `UI-X05` 用它算预警时刻；
 * 刷新令牌走 HttpOnly cookie，不出现在响应体里，故此处没有该字段。
 */
export interface SessionData {
  accessToken: string
  expiresIn: number
  accountId: string
  accountName: string
  roles: string[]
  permissions: string[]
}

/** API-005 列表条目与 API-007 详情：字段同形，只差分页外壳。 */
export interface AccountItem {
  id: string
  accountName: string
  employeeId: string | null
  /**
   * 端标识，闭集见契约 1.7：`HUB`（运营端）、`OWNER`（货主端）。
   * 服务端恒返回，列表与详情据它渲染「端」并决定「所属货主」是否有值。
   */
  client: string
  /** 归属货主，十进制字符串；非货主端账号为 `null`（契约 1.7）。 */
  ownerId: string | null
  /** 归属货主编号，由服务端按 `ownerId` 关联货主档案取得；非货主端或档案缺失为 `null`。 */
  ownerNo: string | null
  /** 归属货主名称，同上。 */
  ownerName: string | null
  status: string
  roles: string[]
  version: number
}

/**
 * 开通账号允许的端，闭集逐字取契约 1.7：只有 `HUB` 与 `OWNER`。
 * `SITE`／`MOBILE` 是单仓端与移动作业端的端标识，开通账号时传它们开不出能登录的账号，
 * 服务端按参数错误拒绝，故不进入本页选项。中文词取 `@wms/dict` 的 `clientLabels`。
 */
export const accountClientOptions = ['HUB', 'OWNER'] as const
export type AccountClient = (typeof accountClientOptions)[number]

/**
 * 开通账号请求体，字段逐字取契约 1.7 与身份模块 API-006：
 * `client` 必填；`ownerId` 仅 `client=OWNER` 时出现、且必须指向存在的货主档案；
 * `client=HUB` 时不得出现 `ownerId`，货主端账号也不绑员工、不得出现 `employeeId`。
 * 两个标识都是十进制字符串，不做数值转换。
 */
export interface AccountCreateBody {
  accountName: string
  client: AccountClient
  employeeId?: string
  ownerId?: string
  initialPassword: string
  roleIds: string[]
}

/** API-010 条目：`permissionCodes[]` 直接用于角色页与授权抽屉，不再逐角色另查。 */
export interface RoleItem {
  id: string
  roleCode: string
  roleName: string
  client: string
  status: string
  version: number
  permissionCodes: string[]
}

/** API-014 条目。 */
export interface PermissionGroup {
  module: string
  moduleName: string
  items: Array<{ code: string; name: string }>
}

/**
 * API-015 条目：`dimension` 闭集 `OWNER`／`WAREHOUSE`，`scopeType` 闭集 `ALL`／`LIST`。
 * 未配置的维度不出现在返回数组里，展示时按「全部」处理。
 */
export interface ScopeItem {
  dimension: 'OWNER' | 'WAREHOUSE'
  scopeType: 'ALL' | 'LIST'
  targetIds: string[]
}

/**
 * API-018 受理结果。本接口没有主键标识：`acceptNo` 是受理号、`result` 与 `errorCode`
 * 是文本，故不受「标识为十进制字符串」口径影响，字段保持原样。
 */
export interface AcceptanceResult {
  acceptNo: string
  status: string
  result?: string | null
  errorCode?: string | null
  createdAt?: string
  finishedAt?: string | null
}

/**
 * API-018 轮询函数，注入给 `AcceptPanel`；查询需要访问令牌，故走统一请求层。
 * 身份模块的 `result` 是文本摘要（不是商品档案的 `result` 对象），`AcceptPanel` 只取受理状态
 * 与失败错误码，此处显式映射一次，避免把身份模块的文本结果塞进受理结果摘要。
 */
export function createAcceptancePoller(http: HttpClient) {
  return async (acceptNo: string) => {
    const data = await http.get<AcceptanceResult>(`/hub/api/acceptances/${acceptNo}`)
    return { status: data.status, errorCode: data.errorCode ?? null }
  }
}

/**
 * 「所属货主」的展示串（`OA-07`、`OA-08`）：`名称（编号）`。
 * 非货主端账号、或服务端未带出货主编号与名称时显示「—」——不显示裸标识，
 * 也不反查货主（契约没有按 `ownerId` 查货主的接口）。
 */
export function accountOwnerLabel(
  item: Pick<AccountItem, 'client' | 'ownerNo' | 'ownerName'> | null | undefined,
): string {
  if (!item || item.client !== 'OWNER') {
    return '—'
  }
  const name = item.ownerName?.trim() ?? ''
  const no = item.ownerNo?.trim() ?? ''
  if (name && no) {
    return `${name}（${no}）`
  }
  if (no) {
    return no
  }
  return name || '—'
}

/**
 * 契约 1.7：开通货主端账号时「未选货主或货主不存在」返回 400（身份模块 `COMMON_9001`）。
 * 文案逐字取 `ui.md`「OA-07」，只在归属货主字段下方就地提示，不改写服务端 message。
 */
export const ACCOUNT_OWNER_BINDING_HINT = '归属货主要求已存在的货主档案'

/**
 * 绑定类 400 的判定：身份模块以 `COMMON_9001` 下发，线上码可能是数字 `9001`，
 * 也可能带符号前缀，故三种写法都在一处判定，页面不各写一份比较。
 */
export function isOwnerBindingError(error: unknown): boolean {
  const code = (error as { code?: number | string } | null)?.code
  return code === 9001 || code === '9001' || code === 'COMMON_9001'
}

/**
 * 账号页受理失败的出口编码，与 `OA-10` 的出口词一一对应（`UI-W01`、`UI-W02`）。
 * 账号开通与账号修改的受理查询属身份模块，页面的出口只有「重新提交」一项。
 */
export const ACCOUNT_RETRY_EXIT = 'resubmit'

/**
 * 受理失败的三段文案（`UI-W07`、`OA-10` 口径）：第二段取集中的错误码映射，
 * 第一段由页面按最近一次动作给，第三段暂不拼——身份模块的错误码未在 `OA-10` 的分派表里
 * 登记对应的「下一步」，此处不自造文案（见返回的规范缺口）。
 * 出口固定给「重新提交」，保证 `UI-I06` 每个失败态都有可做的动作。
 */
export function describeAccountAcceptFailure(errorCode: string): AcceptanceFailureView {
  return {
    message: describeErrorCode(errorCode),
    nextStep: '',
    exits: [{ code: ACCOUNT_RETRY_EXIT, label: '重新提交' }],
  }
}
