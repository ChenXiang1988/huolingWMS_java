/**
 * 单仓端权限点编码与路由准入判定，只此一份。
 *
 * 取值逐字取自变更 `add-three-end-product-archive` 的 `design.md`「前端设计 · 菜单与权限点」，
 * 该处标注「待并入权限矩阵」，同步由主代理完成（`05` 目前尚未登记这两个编码）。
 * 判定只影响展示：功能权限与数据域以服务端校验为准（`05`、INV-19、`UI-I11`）。
 */
import type { RouteMeta } from 'vue-router'

export const PERMISSIONS = {
  archiveSiteRead: 'archive.site.read',
  archiveSiteChange: 'archive.site.change',
} as const

export type PermissionCode = (typeof PERMISSIONS)[keyof typeof PERMISSIONS]

/** 无权限页要联系的角色（`UI-S03`「需联系的角色」）：单仓端角色由运营端权限管理员分配。 */
export const PERMISSION_CONTACT_ROLE = '权限管理员'

export function routePermission(meta: RouteMeta): PermissionCode | undefined {
  return meta.permission
}

export function canVisit(meta: RouteMeta, hasPermission: (code: PermissionCode) => boolean): boolean {
  const required = routePermission(meta)
  return required === undefined || hasPermission(required)
}
