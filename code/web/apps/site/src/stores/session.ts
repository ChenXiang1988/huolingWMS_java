import { defineStore } from 'pinia'
import { createHttp, createSiteSessionApi, newIdempotencyKey } from '@wms/request'
import type { HttpClient, SiteSessionTicket } from '@wms/request'

/**
 * 单仓端会话（契约「单仓端会话与权限」）。
 *
 * 令牌由 Hub 认证服务签发，声明含唯一 `siteId` 与端标识 `SITE`：
 * - 进入应用用站点编码与站点凭证 `credential` 走 API-018 换取 `accessToken`、`siteId`、
 *   `permissions` 与 `expiresIn`；
 * - 令牌即将过期（剩余 5 分钟，`UI-X05`）走 API-019 续期；
 * - 令牌被判失效（HTTP 401，或错误码为一串 `"1002"`／数字 `1002`）时先按 API-019 续期一次，
 *   续不上再清空会话回到会话门按 API-018 重新换取，不留「有页面、无令牌」的错误态；
 * - 多仓声明或无仓声明由服务端返回 `FORBIDDEN_SITE`，前端按 `UI-S03` 无权限处理；
 * - 续期失败保留当前页并给重新登录出口（`ui.md`「单仓端 会话获取与续期」）。
 *
 * 刷新凭证 `refreshCredential` 由 API-018 以 httpOnly cookie 下发，不进响应体，
 * 前端读不到也不落浏览器存储；API-019 由浏览器自动带 cookie，请求体只带 `clientRequestId`。
 * 契约允许续期请求体显式带 `refreshCredential`，本端不读取、不明文持久化该字段。
 * 站点凭证 `credential` 只在换取令牌那一次请求里出现，成功后不留在内存。
 * 功能权限只影响菜单与按钮展示，数据域一律以服务端按令牌中的 `siteId` 的校验为准（`05`、INV-19）。
 */

/** `UI-X05`：剩余 5 分钟开始续期。 */
const RENEW_AHEAD_MS = 5 * 60 * 1000

/**
 * 进行中的 401 续期请求。多个并发请求同时失效时共用一次续期，
 * 避免每来一个 401 就打一次认证入口（契约 API-019「同一刷新凭证并发只产生一个新令牌」）。
 */
let renewing: Promise<boolean> | null = null

/** 有效期（整数秒）换算到期时刻；缺字段或非正数记 `null`，此时只靠主动续期与 401 兜底。 */
function toExpiresAt(expiresIn?: number): number | null {
  return typeof expiresIn === 'number' && Number.isFinite(expiresIn) && expiresIn > 0
    ? Date.now() + expiresIn * 1000
    : null
}

export const useSessionStore = defineStore('site-session', {
  state: () => ({
    token: null as string | null,
    /** 令牌里的唯一仓标识；多仓或无仓已由服务端拒绝。 */
    siteId: null as string | null,
    /** Hub 下行的功能权限副本；`null` 表示尚未换取令牌。 */
    permissions: null as string[] | null,
    /** 到期时刻（毫秒），只存内存。 */
    expiresAt: null as number | null,
    acquiring: false,
    /** 续期失败标志：保留当前页并给重新登录出口。 */
    renewFailed: false,
  }),
  getters: {
    /** 是否已换取到令牌；未换取时由会话门承接 API-018。 */
    established: (state) => state.token !== null,
  },
  actions: {
    /**
     * 未换取令牌时视为「权限未知」：入口照常渲染，功能权限与数据域以服务端校验为准；
     * 换取到权限副本后按副本隐藏菜单与按钮（`05`「菜单与按钮只影响展示，不作为控制手段」）。
     */
    hasPermission(code: string) {
      return this.permissions === null ? true : this.permissions.includes(code)
    },
    apply(ticket: SiteSessionTicket) {
      this.token = ticket.accessToken
      this.siteId = ticket.siteId
      this.permissions = ticket.permissions
      this.expiresAt = toExpiresAt(ticket.expiresIn)
      this.renewFailed = false
    },
    clear() {
      this.token = null
      this.siteId = null
      this.permissions = null
      this.expiresAt = null
      this.renewFailed = false
    },
    /**
     * API-018：用站点编码与站点凭证换取令牌、仓标识与功能权限列表。
     * 凭证只在本次请求里使用，成功后不保存（刷新凭证走 httpOnly cookie）。
     * 错误交调用方按 `UI-S03` 展示。
     */
    async login(siteCode: string, credential: string) {
      this.acquiring = true
      try {
        // 凭证错误由服务端返回 `FORBIDDEN_SITE`／参数错误，不是令牌失效，故不再触发续期（silent）。
        const ticket = await createSiteSessionApi(this.http({ silent: true })).login({
          siteCode: siteCode.trim(),
          credential,
          clientRequestId: newIdempotencyKey(),
        })
        this.apply(ticket)
      } finally {
        this.acquiring = false
      }
    },
    /** API-019：续期。刷新凭证由 httpOnly cookie 带入；失败只置标志、不清当前页，由外壳给重新登录出口。 */
    async refresh() {
      if (!this.token) {
        this.renewFailed = true
        return false
      }
      try {
        // 续期请求自身失败时不再触发续期（silent），避免 401 → 续期 → 401 的递归。
        const ticket = await createSiteSessionApi(this.http({ silent: true })).refresh({
          clientRequestId: newIdempotencyKey(),
        })
        this.apply(ticket)
        return true
      } catch {
        this.renewFailed = true
        return false
      }
    },
    /**
     * 令牌失效（HTTP 401 或令牌失效码）后的出口：先按 API-019 续期一次，
     * 并发失效只发一次续期请求；续期不成再清空会话，由根壳切回会话门重新换取（API-018），
     * 页面不停在错误态（`ui.md`「单仓端 会话获取与续期」：续期失败保留当前页并给重新登录出口）。
     */
    async renewOrExit() {
      if (!renewing) {
        renewing = this.refresh().finally(() => {
          renewing = null
        })
      }
      const renewed = await renewing
      if (!renewed) {
        this.clear()
        this.renewFailed = true
      }
    },
    /** 到期前 5 分钟自动续期（`UI-X05`）；未到期不动，避免每次轮询都打认证入口。 */
    async ensureFresh() {
      if (!this.token || this.expiresAt === null) {
        return
      }
      if (Date.now() < this.expiresAt - RENEW_AHEAD_MS) {
        return
      }
      await this.refresh()
    },
    /**
     * 请求层装配。`silent` 用于换取与续期这两个认证入口自身：
     * 它们失败时不再触发「令牌失效」处置，避免 401 → 续期 → 401 的递归。
     */
    http(options: { silent?: boolean } = {}): HttpClient {
      return createHttp({
        getToken: () => this.token,
        // 401 写回：令牌被拒时清空内存态，回到会话门按 API-018 重新换取。
        setToken: (token, expiresIn) => {
          this.token = token
          this.expiresAt = token === null ? null : toExpiresAt(expiresIn)
        },
        // 单仓端续期要带 `clientRequestId`，由本 store 的 API-019 承担，不用请求层的无体续期。
        refreshPath: null,
        // 令牌失效（401 或 1002，字符串与数字都算）先按 API-019 续期，续不上再回会话门重新换取。
        onUnauthorized: options.silent ? undefined : () => void this.renewOrExit(),
      })
    },
  },
})
