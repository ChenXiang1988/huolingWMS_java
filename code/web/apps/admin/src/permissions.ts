/**
 * 权限点编码与路由准入判定，只此一份。
 *
 * 取值来自 `design.md`「前端设计 · 权限点编码」，页面与权限点的对应关系来自同章「页面与接口映射」。
 * 菜单生成（`AdminLayout`）与路由守卫（`router/index.ts`）都走 `routePermission` 与 `canVisit`，
 * 页面内的按钮权限也用 `PERMISSIONS` 常量，业务代码里不再出现编码字面量。
 */
import type { RouteMeta } from 'vue-router'

export const PERMISSIONS = {
  accountRead: 'hub.account.read',
  accountCreate: 'hub.account.create',
  accountUpdate: 'hub.account.update',
  accountRole: 'hub.account.role',
  roleRead: 'hub.role.read',
  roleCreate: 'hub.role.create',
  roleUpdate: 'hub.role.update',
  roleAssign: 'hub.role.assign',
  roleScope: 'hub.role.scope',
  auditRead: 'hub.audit.read',
  /**
   * 商品档案三个权限点，取自变更 `add-three-end-product-archive` 的 `design.md`
   * 「前端设计 · 菜单与权限点」，该处标注「待并入权限矩阵」，同步由主代理完成。
   */
  archiveHubRead: 'archive.hub.read',
  archiveHubCreate: 'archive.hub.create',
  archiveHubReview: 'archive.hub.review',
} as const

export type PermissionCode = (typeof PERMISSIONS)[keyof typeof PERMISSIONS]

/**
 * 无权限页要联系的角色（`UI-S03`「需联系的角色」）。
 * 依据 `05` 的角色矩阵：建账号、分配角色、配置权限点与数据域都归「权限管理员」。
 */
export const PERMISSION_CONTACT_ROLE = '权限管理员'

/** 路由声明的所需权限点；不声明表示该页不需要权限点（如工作台、无权限页）。 */
export function routePermission(meta: RouteMeta): PermissionCode | undefined {
  return meta.permission
}

/** 菜单可见性与路由守卫共用：无声明或持有该权限点即放行。 */
export function canVisit(meta: RouteMeta, hasPermission: (code: PermissionCode) => boolean): boolean {
  const required = routePermission(meta)
  return required === undefined || hasPermission(required)
}
