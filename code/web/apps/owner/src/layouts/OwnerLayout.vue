<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AppShell } from '@wms/design'
import { useSessionStore } from '../stores/session'
import { canVisit } from '../permissions'

/**
 * 货主端外壳（12a「货主端补充」：顶栏 + 一级菜单，无二级折叠）。
 * 菜单 = 路由表 ∩ 权限集，与守卫共用 `canVisit`；无权限的项不渲染、不置灰（`UI-X06`）。
 */
const session = useSessionStore()
const route = useRoute()
const router = useRouter()

const menus = computed(() =>
  router
    .getRoutes()
    .filter((item) => item.meta.menu && canVisit(item.meta, (code) => session.hasPermission(code)))
    .map((item) => ({ path: item.path, title: item.meta.title as string })),
)
</script>

<template>
  <AppShell :menus="menus" :active="route.path" brand="货灵 WMS · 货主端">
    <router-view />
  </AppShell>
</template>
