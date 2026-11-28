<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AppShell, ModuleTabs } from '@wms/design'
import { useAuthStore } from '../stores/auth'
import { canVisit } from '../permissions'
import { buildNav, tabsOf } from '../router/navigation'
import { useSessionExpiryWarning } from '../composables/useSessionExpiryWarning'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

/** UI-X05：会话到期前预警只在有外壳的页面启用，随外壳挂载与卸载。 */
useSessionExpiryWarning()

/** UI-P12：顶栏品牌区为平台名与副标题，端名不再进顶栏。 */
const brand = { title: '货灵', subtitle: 'WMS 仓储管理平台' }

/**
 * 模块树 = 路由表 ∩ 权限集，与守卫共用 `canVisit`；无权限的页面在侧栏与页签都不渲染、
 * 不置灰（`UI-X06`），模块下全部页面无权限时该模块不出现（`UI-P13`）。
 */
const nav = computed(() =>
  buildNav(
    router
      .getRoutes()
      .filter((item) => item.meta.menu && canVisit(item.meta, (code) => auth.hasPermission(code)))
      .map((item) => ({
        path: item.path,
        title: item.meta.title ?? item.path,
        group: item.meta.group,
      })),
  ),
)

/** 页签 = 当前页所在模块的页面，顺序与侧栏二级一致；单页模块不渲染（`UI-P16`）。 */
const tabs = computed(() => tabsOf(nav.value, route.path))

/** 用户区只读登录态：账号名与角色都来自 API-004，不额外请求。 */
const user = computed(() =>
  auth.profile ? { name: auth.profile.accountName, roles: auth.profile.roles } : undefined,
)

/**
 * 侧栏与模块页签的统一跳转出口。
 * 点当前页不重复跳转，页面内的筛选、排序与页码按 `UI-X04` 由各页自己写地址栏，外壳不重置。
 */
function navigate(path: string) {
  if (path !== route.path) {
    void router.push(path)
  }
}

async function handleLogout() {
  await auth.logout()
  router.push('/login')
}
</script>

<template>
  <AppShell
    :nav="nav"
    :active="route.path"
    :brand="brand"
    :user="user"
    collapsible
    @navigate="navigate"
    @logout="handleLogout"
  >
    <!-- UI-P16：模块页签在内容区顶部、页头之上，由外壳插槽承载 -->
    <ModuleTabs :tabs="tabs" :active="route.path" @navigate="navigate" />
    <router-view />
  </AppShell>
</template>
