/**
 * 身份与权限接口的响应形状，只此一份。
 * 字段逐项对应设计章的契约条目：API-005/007（账号）、API-009（分配角色）、
 * API-010（角色）、API-014（权限点）、API-015（数据域）、API-018（受理结果）。
 * 页面只引用本文件，不各写一份请求体或响应体。
 *
 * 标识口径按契约「标识、类型与枚举约定」：`id`、`accountId`、`employeeId`、
 * `roleIds[]`、`targetIds[]` 出入参一律为十进制字符串。主键是雪花号
 * （如 `2125394920742858753`），超出 JavaScript 安全整数上界 `9007199254740991`，
 * 按 JSON 数字传输会在浏览器侧静默丢精度。前端只原样透传字符串，不做
 * `Number()`／`parseInt` 转换，也不做数值比较；`version` 仍是 JSON 整数。
 */
import type { HttpClient } from '@wms/request'

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
  status: string
  roles: string[]
  version: number
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
