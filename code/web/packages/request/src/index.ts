/**
 * 请求层：统一响应包、错误码、令牌注入、401 续期一次、写接口幂等键。
 * 页面不直接发起原始请求，也不手写请求体结构。
 *
 * 令牌失效判定只在 `isTokenInvalidCode` 里做一次：数字 `1002` 与字符串 `"1002"` 都算失效，
 * HTTP 401 即使错误体缺码也归一到该码；有续期入口的端先续期重放，否则调用 `onUnauthorized`
 * 让所属端给出续期或重新登录出口（契约「AUTH_1002 续期后重试，失败跳登录」）。
 *
 * 商品档案、变更申请与受理查询的接口声明在同目录 `archive.ts`，货主档案（API-021 至 API-027）
 * 在 `owner-archive.ts`，单仓端会话（API-018、API-019）在 `session.ts`，幂等键生成在 `keys.ts`，
 * 字符串错误码文案在 `errors.ts`；五者都从这里统一导出，应用只引 `@wms/request`。
 */
import { archiveErrorHints } from './errors'

export { archiveErrorHints } from './errors'
export { newIdempotencyKey } from './keys'
export * from './archive'
export * from './owner-archive'
export * from './session'

/**
 * 错误明细条目，见契约「错误响应结构」：`details` 为元素 `{field, reason}` 的数组。
 * 货主档案契约（1.1）为本变更补齐该数组，页面的字段级错误按 `details[].field` 就地定位，
 * 不再靠解析 `message` 猜字段。
 */
export interface ApiErrorDetail {
  field: string
  reason: string
}

export interface ApiResponse<T> {
  /**
   * 业务码。身份与权限接口用数字码，商品档案接口按其契约用字符串符号码
   * （如 `RULE_DUPLICATE_CODE`）；两套码共用同一个响应包，故此处取并集。
   * 成功码两种写法并存：契约「通用约定」规定成功码固定为字符串 `"0"`，
   * 平台响应包同时兼容数字 `0`，判定只在 `isSuccessCode` 做一次（错误码仍按符号码处理），
   * 页面只经 `describeApiError` 取文案，不各自判断码值。
   */
  code: number | string
  message: string
  data: T
  traceId: string
  retryable: boolean
  /** 按需出现的字段级错误明细，取契约「错误响应结构」的 `details`。 */
  details?: ApiErrorDetail[]
  /** 按需出现的退避毫秒数，取契约「错误响应结构」的 `retryAfterMs`。 */
  retryAfterMs?: number
}

/**
 * 成功码判定：字符串 `"0"` 与数字 `0` 都算成功。
 * 商品档案与受理查询的契约成功码是字符串 `"0"`，身份与权限接口沿用数字 `0`，
 * 只在这里统一，页面与接口声明不再各自比较码值。
 */
export function isSuccessCode(code: number | string): boolean {
  return code === 0 || code === '0'
}

export class ApiError extends Error {
  constructor(
    public readonly code: number | string,
    message: string,
    public readonly retryable: boolean,
    public readonly traceId?: string,
    /** 字段级错误明细，取契约 `details`；`PARAM_INVALID` 的就地定位依赖它（`OA-10`）。 */
    public readonly details: ApiErrorDetail[] = [],
    /** 退避毫秒数，取契约 `retryAfterMs`；`RATE_LIMITED`、`IDEMPOTENT_IN_PROGRESS` 用它排重试。 */
    public readonly retryAfterMs?: number,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/**
 * 令牌无效或过期的平台错误码（`AUTH_1002` 的线上码）。
 * 身份模块按数字码下发，档案接口的响应包也可能把同一个码写成字符串，
 * 故判定只走 `isTokenInvalidCode`，不在各处比字面量。
 */
export const TOKEN_INVALID_CODE = 1002

/**
 * 令牌失效判定：数字 `1002` 与字符串 `"1002"` 都算失效（契约「通用约定」：错误体 `code` 为字符串符号码，
 * 令牌无效沿用平台既有 401 与数字错误码）。HTTP 401 即使错误体缺码也按失效处理，由请求层统一归一到该码。
 */
export function isTokenInvalidCode(code: number | string | null | undefined): boolean {
  return code === TOKEN_INVALID_CODE || code === String(TOKEN_INVALID_CODE)
}

/** 错误对象是否令牌失效。 */
export function isTokenInvalidError(error: unknown): boolean {
  return error instanceof ApiError && isTokenInvalidCode(error.code)
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

/**
 * API-001／API-002 响应里与令牌有效期相关的字段，按契约逐字取值。
 * 这里只取续期要回传的两项，完整响应形状由各应用的接口声明承载，不在此重复一份。
 */
interface TokenPayload {
  accessToken?: string
  /** 整数秒，见契约「API-001 登录」的 `expiresIn`。 */
  expiresIn?: number
}

interface HttpOptions {
  baseUrl?: string
  /**
   * 静默续期的接口路径（无请求体）。默认取运营端的 `/hub/api/auth/refresh`。
   * 单仓端续期走契约 API-019 且要带 `clientRequestId`，由单仓端 store 自己发起（`refreshPath: null`），
   * 货主端在本次契约章内没有续期入口，也传 `null`；两者都不发起跨端续期（契约「端之间路径不混用」）。
   */
  refreshPath?: string | null
  getToken: () => string | null
  /**
   * 令牌写回。`expiresIn` 是 API-001／API-002 响应里的有效期（秒），
   * 由调用方据此算到期时刻；401 静默续期与主动续期走同一个入口，避免两套计时。
   */
  setToken: (token: string | null, expiresIn?: number) => void
  /**
   * 令牌失效且续期不成的出口：所属端据此清会话、给续期或重新登录出口
   * （运营端回登录页、单仓端回会话门）。不传则该端只把错误交给页面展示。
   */
  onUnauthorized?: () => void
}

export function createHttp(options: HttpOptions) {
  const baseUrl = options.baseUrl ?? ''
  const refreshPath = options.refreshPath === undefined ? '/hub/api/auth/refresh' : options.refreshPath

  /**
   * 读取响应体。错误响应可能不是 JSON（网关或静态错误页），此时返回 `null`，
   * 由调用方按 HTTP 状态码处置，不让 `SyntaxError` 冒到页面。
   */
  async function readPayload(response: Response): Promise<unknown | null> {
    try {
      return await response.json()
    } catch {
      return null
    }
  }

  async function send(method: string, path: string, body?: unknown, extra?: Record<string, string>) {
    const headers: Record<string, string> = { 'Content-Type': 'application/json', ...extra }
    const token = options.getToken()
    if (token) {
      headers.Authorization = `Bearer ${token}`
    }
    const response = await fetch(baseUrl + path, {
      method,
      headers,
      credentials: 'include',
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const payload = (await readPayload(response)) as ApiResponse<unknown> | null
    if (payload === null) {
      throw new ApiError(
        response.status === 401 ? TOKEN_INVALID_CODE : response.status,
        '请求失败，请稍后重试',
        response.status === 401 || response.status >= 500,
      )
    }
    if (!response.ok || !isSuccessCode(payload.code)) {
      /*
       * 令牌失效判定：错误体自己带的码说了算，字符串 `"1002"` 与数字 `1002` 都算；
       * 只有 401 且错误体没带码时才归一到 1002（如网关直接拦下的空体 401）。
       * 身份模块的 401 还可能是 `1001`、`1003`、`1004`，不能一律当成令牌失效。
       */
      const code = payload.code
      const hasCode = code !== undefined && code !== null && code !== ''
      const tokenInvalid = isTokenInvalidCode(code) || (response.status === 401 && !hasCode)
      throw new ApiError(
        tokenInvalid ? TOKEN_INVALID_CODE : code,
        payload.message || '请求失败，请稍后重试',
        payload.retryable,
        payload.traceId,
        payload.details ?? [],
        payload.retryAfterMs,
      )
    }
    return payload.data
  }

  async function refreshToken(): Promise<boolean> {
    // 本端没有续期接口时直接按会话失效处理，不改用另一端路径（契约「端之间路径不混用」）。
    if (refreshPath === null) {
      return false
    }
    try {
      const data = (await send('POST', refreshPath)) as TokenPayload | null
      if (!data?.accessToken) {
        return false
      }
      options.setToken(data.accessToken, data.expiresIn)
      return true
    } catch {
      return false
    }
  }

  async function request<T>(method: string, path: string, body?: unknown, extra?: Record<string, string>): Promise<T> {
    try {
      return (await send(method, path, body, extra)) as T
    } catch (error) {
      // 只有令牌失效才走续期或重新登录出口，其它错误原样抛给页面按错误码处置。
      if (!isTokenInvalidError(error)) {
        throw error
      }
      // 本端有续期入口时先静默续期一次，同一请求原样重放（契约「AUTH_1002 续期后重试」）。
      if (await refreshToken()) {
        return (await send(method, path, body, extra)) as T
      }
      // 续期入口缺失或续期失败：交给所属端处置，单仓端据此续期或回会话门，运营端回登录页。
      options.onUnauthorized?.()
      throw error
    }
  }

  return {
    get: <T>(path: string) => request<T>('GET', path),
    post: <T>(path: string, body?: unknown, idempotencyKey?: string) =>
      request<T>('POST', path, body, idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined),
    put: <T>(path: string, body?: unknown, idempotencyKey?: string) =>
      request<T>('PUT', path, body, idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined),
    patch: <T>(path: string, body?: unknown, idempotencyKey?: string) =>
      request<T>('PATCH', path, body, idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined),
  }
}

export type HttpClient = ReturnType<typeof createHttp>

/**
 * 错误码到提示的映射，只此一份。取值与措辞取自契约章「错误码表」的「调用方处置」列。
 * 未登记的数字段回落后端 message（如 `COMMON_9001` 的具体字段说明，前端不复制一份）。
 * `1002` 为 `AUTH_1002` 的线上码，处置是「续期后重试，失败跳登录」，提示只说结果不替用户点按钮。
 */
const errorHints: Record<number, string> = {
  1002: '登录状态已失效，请重新登录',
  1003: '账号已停用，请联系管理员',
  2001: '当前账号没有该操作所需的权限点',
  2004: '不能给自己授权',
  2005: '至少保留一个可用的权限管理员',
  3001: '账号名已存在，请换名重提',
  3002: '该员工已有账号，请改用已有账号',
  3003: '账号不存在，请刷新列表',
  4001: '角色不存在，请刷新列表',
  4002: '角色端标识与账号不匹配，请改选本端角色',
  5001: '提交了不存在的权限点，请刷新清单后重提',
  6001: '数据域维度只支持货主与仓',
  6002: '指定列表须给出有效标识，全部不应携带标识',
  9002: '请求过于频繁，请稍后重试',
  9003: '服务暂时不可用，请稍后重试',
  9004: '数据已被他人修改，请刷新后重提',
  9005: '本次提交与上次不同，请刷新后重提',
}

/**
 * 单个错误码到提示文案的映射，只此一份：`describeApiError` 与受理条的失败分派共用。
 * 数字码（含以字符串下发的数字码）走 `errorHints`，字符串符号码走 `archiveErrorHints`；
 * 两表都未登记的返回空串，由调用方回落到服务端 `message`，不在此自造文案（`UI-W07`）。
 */
export function describeErrorCode(code: number | string | null | undefined): string {
  if (code === null || code === undefined || code === '') {
    return ''
  }
  if (typeof code === 'number') {
    return errorHints[code] ?? ''
  }
  const symbol = archiveErrorHints[code]
  if (symbol) {
    return symbol
  }
  /*
   * 身份与权限接口的受理结果存的是符号码（如 `ACCOUNT_3001`），同步响应下发的是数字段，
   * 两者按模块规则「取最后一个下划线后的数字」互相折算，故这里也按同一规则回落一次。
   */
  const suffix = code.slice(code.lastIndexOf('_') + 1)
  const numeric = Number(suffix)
  return Number.isInteger(numeric) ? (errorHints[numeric] ?? '') : ''
}

export function describeApiError(error: unknown): string {
  if (error instanceof ApiError) {
    const hint = describeErrorCode(error.code) || error.message
    return error.traceId ? `${hint}（${error.traceId}）` : hint
  }
  return '请求失败，请稍后重试'
}
