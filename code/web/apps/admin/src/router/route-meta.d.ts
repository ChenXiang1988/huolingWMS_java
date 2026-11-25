import type { PermissionCode } from '../permissions'

declare module 'vue-router' {
  interface RouteMeta {
    /** 公开页（登录页）：不要求访问令牌。 */
    public?: boolean
    /** 是否进左侧菜单。 */
    menu?: boolean
    /** 菜单与页面标题。 */
    title?: string
    /** 进入该路由所需的权限点，取自 `design.md`「页面与权限点映射」；不写表示不需要权限点。 */
    permission?: PermissionCode
  }
}
