import { defineStore } from 'pinia'
import { createHttp, ApiError } from '@wms/request'
import type { SessionData } from '../api/identity'

/**
 * API-001／002／004 的会话身份。标识一律为十进制字符串（契约「标识、类型与枚举约定」）：
 * 账号主键 `id`、数据域里的 `ownerIds[]`／`warehouseIds[]` 都是雪花号，转数字会丢精度。
 */
interface Profile {
  id: string
  accountName: string
  roles: string[]
  permissions: string[]
  dataScope: {
    allOwners: boolean
    ownerIds: string[]
    allWarehouses: boolean
    warehouseIds: string[]
  }
}

/**
 * 访问令牌的到期时刻，唯一计算点。入参是 API-001／API-002 响应里的 `expiresIn`（整数秒）。
 * 缺字段、非数值或非正数时记 `null`（到期时间未知）：此时不排 `UI-X05` 的预警，
 * 只靠请求层 401 的一次性续期兜底，避免用错误的到期时刻误弹。
 */
function toExpiresAt(expiresIn: number | undefined): number | null {
  return typeof expiresIn === 'number' && Number.isFinite(expiresIn) && expiresIn > 0
    ? Date.now() + expiresIn * 1000
    : null
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: null as string | null,
    /** 到期时刻（毫秒），只存内存，与访问令牌同生命周期。`UI-X05` 的排定依据。 */
    expiresAt: null as number | null,
    profile: null as Profile | null,
  }),
  getters: {
    permissions: (state) => state.profile?.permissions ?? [],
    loggedIn: (state) => state.token !== null,
  },
  actions: {
    hasPermission(code: string) {
      return this.permissions.includes(code)
    },
    /** 登录与续期的响应同形（API-001／002），令牌与到期时刻必须成对更新。 */
    applySession(data: SessionData) {
      this.token = data.accessToken
      this.expiresAt = toExpiresAt(data.expiresIn)
    },
    http() {
      return createHttp({
        getToken: () => this.token,
        // 401 静默续期由请求层写回令牌，`expiresIn` 一并带回来，衔接同一个到期时刻。
        setToken: (token, expiresIn) => {
          this.token = token
          this.expiresAt = token === null ? null : toExpiresAt(expiresIn)
        },
        onUnauthorized: () => {
          this.token = null
          this.profile = null
          this.expiresAt = null
          window.location.href = '/login'
        },
      })
    },
    async login(accountName: string, password: string) {
      const data = await this.http().post<SessionData>('/hub/api/auth/login', {
        accountName,
        password,
      })
      this.applySession(data)
      try {
        await this.loadProfile()
      } catch (error) {
        // 拿到令牌却取不到权限集时不留半登录状态，交给登录页报错。
        this.$reset()
        throw error
      }
    },
    async loadProfile() {
      this.profile = await this.http().get<Profile>('/hub/api/auth/me')
    },
    /**
     * 访问令牌只在内存，刷新页面后凭 HttpOnly cookie 静默续期。
     * 失败时清空内存态：否则会出现「有令牌、无权限集」，守卫每次判定都要重试续期而反复回跳。
     */
    async tryRefresh() {
      try {
        const data = await this.http().post<SessionData>('/hub/api/auth/refresh')
        this.applySession(data)
        await this.loadProfile()
        return true
      } catch {
        this.$reset()
        return false
      }
    },
    /**
     * API-002：`UI-X05` 的「立即续期」。与 401 静默续期同一个接口，差别只在错误处理：
     * 这里把错误抛给调用方，由预警框按错误码处置（`AUTH_1004` 跳登录，其余就地展示），
     * 不能像 `tryRefresh` 那样一律当会话结束。
     */
    async refreshSession() {
      const data = await this.http().post<SessionData>('/hub/api/auth/refresh')
      this.applySession(data)
      await this.loadProfile()
    },
    async logout() {
      try {
        await this.http().post('/hub/api/auth/logout')
      } catch (error) {
        if (!(error instanceof ApiError)) {
          throw error
        }
      }
      this.token = null
      this.profile = null
      this.expiresAt = null
    },
  },
})
