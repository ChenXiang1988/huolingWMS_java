import { defineStore } from 'pinia'
import { createHttp } from '@wms/request'

/**
 * 货主端会话。
 *
 * 货主端登录、续期与权限集查询接口不在本次变更的接口契约（API-001~API-014）内，
 * `add-hub-auth-and-console-shell` 的「不做」也已明确写出「单仓端、货主端与移动作业端的登录」。
 * 因此本端不自造接口路径：请求带平台下发的会话 cookie 取数（请求层 `credentials: 'include'`），
 * 也不调用运营端的续期路径（契约「端之间路径不混用」，故 `refreshPath: null`）。
 *
 * `permissions` 为 `null` 表示本端会话接口尚未提供权限集：此时入口照常渲染，
 * 功能权限与数据域一律以服务端校验为准（`05`「菜单与按钮只影响展示，不作为控制手段」、INV-19）。
 * 会话接口补齐后，只需在此填入 `permissions`（与可选令牌），页面与路由守卫不用改。
 */
export const useSessionStore = defineStore('owner-session', {
  state: () => ({
    permissions: null as string[] | null,
  }),
  actions: {
    hasPermission(code: string) {
      return this.permissions === null ? true : this.permissions.includes(code)
    },
    http() {
      return createHttp({
        getToken: () => null,
        setToken: () => {
          // 货主端令牌由平台会话 cookie 承载，前端不保存
        },
        refreshPath: null,
      })
    },
  },
})
