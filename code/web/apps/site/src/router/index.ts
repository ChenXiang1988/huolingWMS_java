import { createRouter, createWebHistory } from 'vue-router'
import type { RouteLocationNormalized } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { PERMISSIONS, canVisit, routePermission } from '../permissions'

/**
 * 单仓端路由。两个页面路径逐字取自变更 `add-three-end-product-archive` 的 `design.md`
 * 「前端设计 · 页面清单与路由」：`/site/archives`、`/site/archive-change-requests`。
 */
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      component: () => import('../layouts/SiteLayout.vue'),
      redirect: '/site/archives',
      children: [
        {
          path: 'site/archives',
          name: 'site-archives',
          component: () => import('../views/ArchivesView.vue'),
          meta: { title: '商品档案', menu: true, permission: PERMISSIONS.archiveSiteRead },
        },
        {
          path: 'site/archive-change-requests',
          name: 'site-archive-change-requests',
          component: () => import('../views/ChangeRequestsView.vue'),
          meta: { title: '变更申请', menu: true, permission: PERMISSIONS.archiveSiteRead },
        },
        {
          path: 'site/forbidden',
          name: 'forbidden',
          component: () => import('../views/ForbiddenView.vue'),
          meta: { title: '无权访问' },
        },
      ],
    },
  ],
})

/** 权限不足按 `UI-X06`：入口不渲染，直接访问由守卫转到无权限页（`UI-S03`）。 */
router.beforeEach((to: RouteLocationNormalized) => {
  const session = useSessionStore()
  if (!canVisit(to.meta, (code) => session.hasPermission(code))) {
    return {
      name: 'forbidden',
      query: { permission: routePermission(to.meta) ?? '', from: to.fullPath },
    }
  }
  return true
})
