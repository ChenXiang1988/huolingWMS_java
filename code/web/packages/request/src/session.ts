/**
 * 单仓端会话接口（API-018、API-019），只此一份。
 *
 * 契约「单仓端会话与权限」：站点令牌由 Hub 认证服务签发，令牌声明含唯一 `siteId` 与端标识 `SITE`；
 * 换取令牌走 API-018，续期走 API-019。数据域取令牌中的唯一 `siteId`，缺少或多于一个返回 `FORBIDDEN_SITE`。
 *
 * 会话字段逐字取契约字段表：请求侧 `siteCode`、`credential`、`refreshCredential`、`clientRequestId`，
 * 响应侧 `accessToken`、`siteId`、`permissions`、`expiresIn`（整数秒）。
 * 刷新凭证由 API-018 以 httpOnly cookie 下发，不进响应体，前端不读取也不落存储；
 * API-019 由浏览器自动携带 cookie，缺省请求体只带 `clientRequestId`；
 * 契约允许请求体显式带 `refreshCredential`，本层在类型上留出该字段，但不读取、不持久化明文。
 */
import { newIdempotencyKey } from './keys'
import type { RequestSender } from './archive'

/**
 * API-018／API-019 的响应：令牌、唯一仓标识、功能权限列表与有效期。
 * 两个接口同形，故共用一个形状。
 */
export interface SiteSessionTicket {
  accessToken: string
  /** 唯一仓标识，十进制字符串；多仓声明由服务端拒绝（`FORBIDDEN_SITE`）。 */
  siteId: string
  permissions: string[]
  /** 整数秒，与运营端登录响应同口径。 */
  expiresIn: number
}

/** API-018 请求体：站点编码、站点凭证与客户端请求号。 */
export interface SiteLoginBody {
  siteCode: string
  /** 站点凭证。 */
  credential: string
  /** 幂等键载体，见 API-018「幂等键，取 `clientRequestId`」。 */
  clientRequestId: string
}

/**
 * API-019 请求体：客户端请求号，可选显式刷新凭证。
 * 刷新凭证默认由 API-018 以 httpOnly cookie 下发、浏览器自动携带，前端读不到也不落存储；
 * `refreshCredential` 只在调用方显式持有并传入时才出现，绝不明文持久化到 localStorage、
 * sessionStorage 或 Pinia 的持久化态（契约「刷新凭证用 httpOnly cookie 下发，不进响应体」）。
 */
export interface SiteRefreshBody {
  /** 可选：调用方显式提供的刷新凭证；不提供时由 httpOnly cookie 承载。 */
  refreshCredential?: string
  clientRequestId: string
}

/** 单仓端会话接口：两个入口都只回令牌，不承载业务结果。 */
export function createSiteSessionApi(http: RequestSender) {
  return {
    /** API-018 换取单仓端令牌；端之间路径不混用，认证入口固定在 `/api/hub/auth/site-login`。 */
    login: (body: SiteLoginBody, idempotencyKey: string = newIdempotencyKey()) =>
      http.post<SiteSessionTicket>('/api/hub/auth/site-login', body, idempotencyKey),
    /** API-019 续期单仓端令牌；端标识必须为 `SITE`。 */
    refresh: (body: SiteRefreshBody, idempotencyKey: string = newIdempotencyKey()) =>
      http.post<SiteSessionTicket>('/api/hub/auth/refresh', body, idempotencyKey),
  }
}
