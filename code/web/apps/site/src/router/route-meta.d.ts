import type { PermissionCode } from '../permissions'

declare module 'vue-router' {
  interface RouteMeta {
    /** 是否进左侧菜单。 */
    menu?: boolean
    /** 菜单与页面标题。 */
    title?: string
    /** 进入该路由所需的权限点；不写表示不需要权限点。 */
    permission?: PermissionCode
  }
}
