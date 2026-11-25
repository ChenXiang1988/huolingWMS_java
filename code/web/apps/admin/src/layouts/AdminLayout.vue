<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AppShell } from '@wms/design'
import { useAuthStore } from '../stores/auth'
import { canVisit } from '../permissions'
import { useSessionExpiryWarning } from '../composables/useSessionExpiryWarning'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const collapsed = ref(false)

/** UI-X05：会话到期前预警只在有外壳的页面启用，随外壳挂载与卸载。 */
useSessionExpiryWarning()

/** 菜单 = 路由表 ∩ 权限集，与守卫共用 `canVisit`；无权限的项不渲染、不置灰（UI-X06）。 */
const menus = computed(() =>
  router
    .getRoutes()
    .filter((item) => item.meta.menu && canVisit(item.meta, (code) => auth.hasPermission(code)))
    .map((item) => ({ path: item.path, title: item.meta.title as string })),
)

async function handleLogout() {
  await auth.logout()
  router.push('/login')
}
</script>

<template>
  <AppShell :menus="menus" :active="route.path" :collapsed="collapsed">
    <template #actions>
      <span style="margin-right: 12px">{{ auth.profile?.accountName }}</span>
      <el-button text @click="handleLogout">退出登录</el-button>
    </template>
    <router-view />
  </AppShell>
</template>
