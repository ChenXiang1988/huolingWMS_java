<script setup lang="ts">
/**
 * 三端共用的导航与画布外壳，对应 12a 的 `UI-P04`（顶栏 56、侧栏展开 200 收起 64、内容区居中）。
 * 品牌名由端传入：三端各自独立部署，外壳只有这一份，端名不写死在组件里。
 */
withDefaults(
  defineProps<{
    menus: Array<{ path: string; title: string }>
    active: string
    collapsed?: boolean
    /** 端名，取端枚举的中文词（`UI-W19`），默认运营端。 */
    brand?: string
  }>(),
  { brand: '货灵 WMS · 运营后台' },
)
</script>

<template>
  <div class="app-shell">
    <header class="app-topbar">
      <div class="app-brand">{{ brand }}</div>
      <div class="app-topbar-actions">
        <slot name="actions" />
      </div>
    </header>
    <div class="app-body">
      <aside class="app-sidebar" :class="{ collapsed: collapsed }">
        <el-menu :default-active="active" :collapse="collapsed" router class="app-menu">
          <el-menu-item v-for="menu in menus" :key="menu.path" :index="menu.path">
            {{ menu.title }}
          </el-menu-item>
        </el-menu>
      </aside>
      <main class="app-content">
        <slot />
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  background: var(--el-bg-color-page);
}
.app-topbar {
  height: var(--wms-topbar-height);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--wms-page-padding);
  background: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.app-brand {
  font-size: 16px;
  font-weight: 600;
}
.app-body {
  display: flex;
  min-height: calc(100vh - var(--wms-topbar-height));
}
.app-sidebar {
  width: var(--wms-sidebar-width);
  background: var(--el-bg-color);
  border-right: 1px solid var(--el-border-color-lighter);
  transition: width 0.2s;
}
.app-sidebar.collapsed {
  width: var(--wms-sidebar-width-collapsed);
}
.app-menu {
  border-right: none;
}
.app-content {
  flex: 1;
  min-width: var(--wms-content-min-width);
  max-width: var(--wms-content-max-width);
  margin: 0 auto;
  padding: var(--wms-page-padding);
  box-sizing: border-box;
}
</style>
