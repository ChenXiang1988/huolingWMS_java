import { createRouter, createWebHistory } from 'vue-router'
import type { RouteLocationNormalized } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { PERMISSIONS, canVisit, routePermission } from '../permissions'
import { HOME_PATH } from './navigation'

/**
 * 路由元数据只声明所属模块，模块图标与模块顺序集中在 `router/navigation.ts`，
 * 同一模块的多个页面不再各写一遍图标（`design.md`「模块与路由元数据」）。
 */
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      component: () => import('../layouts/AdminLayout.vue'),
      redirect: HOME_PATH,
      children: [
        {
          path: 'home',
          name: 'home',
          component: () => import('../views/HomeView.vue'),
          meta: { title: '工作台', menu: true, group: '概览' },
        },
        {
          path: 'accounts',
          name: 'accounts',
          component: () => import('../views/AccountsView.vue'),
          meta: { title: '账号管理', menu: true, permission: PERMISSIONS.accountRead, group: '权限与审计' },
        },
        {
          path: 'roles',
          name: 'roles',
          component: () => import('../views/RolesView.vue'),
          meta: { title: '角色管理', menu: true, permission: PERMISSIONS.roleRead, group: '权限与审计' },
        },
        {
          path: 'audit',
          name: 'audit',
          component: () => import('../views/AuditView.vue'),
          meta: { title: '审计日志', menu: true, permission: PERMISSIONS.auditRead, group: '权限与审计' },
        },
        /**
         * 商品档案三页，路由逐字取自变更 `add-three-end-product-archive` 的 `design.md`
         * 「前端设计 · 页面清单与路由」：`/hub/archives`、`/hub/archives/review`、
         * `/hub/archive-change-requests`。
         */
        {
          path: 'hub/archives',
          name: 'hub-archives',
          component: () => import('../views/ArchivesView.vue'),
          meta: { title: '商品档案', menu: true, permission: PERMISSIONS.archiveHubRead, group: '主数据' },
        },
        {
          path: 'hub/archives/review',
          name: 'hub-archive-review',
          component: () => import('../views/ArchiveReviewView.vue'),
          meta: {
            title: '档案审核',
            menu: true,
            permission: PERMISSIONS.archiveHubReview,
            group: '主数据',
          },
        },
        {
          path: 'hub/archive-change-requests',
          name: 'hub-archive-change-requests',
          component: () => import('../views/ArchiveChangeRequestsView.vue'),
          meta: {
            title: '变更申请审核',
            menu: true,
            permission: PERMISSIONS.archiveHubReview,
            group: '主数据',
          },
        },
        /**
         * 货主档案三页，路由逐字取自变更 `add-owner-archive` 的 `design.md`
         * 「前端设计 · 页面清单与路由」：`/hub/owner-archives`、`.../new`、`.../:ownerNo/edit`。
         * 列表页进菜单（`menu: true`），表单两页不进菜单，由守卫按 `canVisit` 拦截，
         * 直达无权限时落 `ForbiddenView`（`UI-S03`）。`ownerNo` 作路径参数，
         * 与 API-022、API-024 的寻址口径一致（契约「对外标识一律用 `ownerNo` 寻址」）。
         */
        {
          path: 'hub/owner-archives',
          name: 'hub-owner-archives',
          component: () => import('../views/OwnerArchivesView.vue'),
          meta: {
            title: '货主档案',
            menu: true,
            permission: PERMISSIONS.ownerHubRead,
            group: '主数据',
          },
        },
        {
          path: 'hub/owner-archives/new',
          name: 'hub-owner-archive-create',
          component: () => import('../views/OwnerArchiveFormView.vue'),
          meta: {
            title: '新建货主档案',
            permission: PERMISSIONS.ownerHubWrite,
            breadcrumb: ['货主档案', '新建货主档案'],
          },
        },
        {
          path: 'hub/owner-archives/:ownerNo/edit',
          name: 'hub-owner-archive-edit',
          component: () => import('../views/OwnerArchiveFormView.vue'),
          meta: {
            title: '编辑货主档案',
            permission: PERMISSIONS.ownerHubWrite,
            breadcrumb: ['货主档案', '编辑货主档案'],
          },
        },
        {
          path: 'forbidden',
          name: 'forbidden',
          component: () => import('../views/ForbiddenView.vue'),
          meta: { title: '无权访问' },
        },
      ],
    },
  ],
})

/** 会话不可用时清空内存态再回登录页，避免「有令牌但拿不到权限集」在守卫里反复回跳。 */
function toLogin(to: RouteLocationNormalized) {
  const auth = useAuthStore()
  auth.$reset()
  return { path: '/login', query: { redirect: to.fullPath } }
}

/**
 * 守卫顺序按 `design.md`「外壳与路由」：续期 → 补权限集 → 权限点判定。
 * 权限点判定用 `canVisit`，与菜单可见性同源（`permissions.ts`）。
 */
router.beforeEach(async (to) => {
  const auth = useAuthStore()

  // ① 无访问令牌 → 用刷新令牌静默续期；失败跳登录页并记录 redirect
  if (!auth.loggedIn && !(await auth.tryRefresh())) {
    return to.meta.public ? true : toLogin(to)
  }

  // ② 权限集来自 API-004，缺了补一次；补不到按会话失效处理
  if (!auth.profile) {
    try {
      await auth.loadProfile()
    } catch {
      return to.meta.public ? true : toLogin(to)
    }
  }

  // ③ 已登录访问登录页 → 回工作台
  if (to.meta.public) {
    return { path: HOME_PATH }
  }

  // ④ 有令牌但缺权限点 → 无权限页（UI-S03），不跳登录页、不留原路由渲染
  if (!canVisit(to.meta, (code) => auth.hasPermission(code))) {
    return {
      name: 'forbidden',
      query: { permission: routePermission(to.meta) ?? '', from: to.fullPath },
    }
  }

  return true
})
