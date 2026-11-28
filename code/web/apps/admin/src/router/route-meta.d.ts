import type { PermissionCode } from '../permissions'

declare module 'vue-router' {
  interface RouteMeta {
    /** 公开页（登录页）：不要求访问令牌。 */
    public?: boolean
    /** 是否进左侧菜单与模块页签。 */
    menu?: boolean
    /** 菜单与页面标题。 */
    title?: string
    /** 进入该路由所需的权限点，取自 `design.md`「页面与权限点映射」；不写表示不需要权限点。 */
    permission?: PermissionCode
    /**
     * 所属模块，取值见 `design.md`「模块与路由元数据」表；不写归入「其他」，空模块不渲染（`UI-P13`）。
     * 模块图标不写在路由上，集中在 `router/navigation.ts` 的固定表里。
     */
    group?: string
    /**
     * 面包屑文案链，取 `design.md`「页面清单与路由 · 路由元数据」：
     * 首项为来源页标题、次项为当前页标题。外壳当前不渲染面包屑，
     * 该元数据只作路由语义登记，供后续外壳增强时直接取用。
     */
    breadcrumb?: string[]
  }
}
