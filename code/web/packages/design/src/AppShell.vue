<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Component } from 'vue'
import { ArrowDown, Expand, Fold, QuestionFilled } from '@element-plus/icons-vue'
import { accountStatusLabels, archiveStatusLabels } from '@wms/dict'
import ActionDrawer from './ActionDrawer.vue'

/**
 * 三端共用的导航与画布外壳，对应 12a 的 `UI-P04`、`UI-P12`、`UI-P13`。
 * 品牌名由端传入：三端各自独立部署，外壳只有这一份，端名不写死在组件里。
 *
 * 两种形态，由是否传 `nav` 决定：
 * - 传 `nav`（运营端）：顶栏品牌区 + 帮助与用户区，侧栏两级模块树 + 底部工具条，
 *   层级由模块页签承担，无面包屑、无顶栏折叠按钮。
 * - 不传 `nav`（货主端、单仓端）：沿用旧形态，顶栏端名 + 平铺菜单，尺寸与改造前一致。
 * 设计包不依赖 router，侧栏与页签的跳转一律通过 `navigate` 事件交给调用方。
 */
type NavModule = {
  title: string
  icon?: Component
  children: Array<{ path: string; title: string }>
}

const props = withDefaults(
  defineProps<{
    /** 平铺菜单；只在未传 `nav` 时按此渲染（货主端、单仓端）。 */
    menus?: Array<{ path: string; title: string }>
    active: string
    /** 受控折叠态；仅在未开启 `collapsible` 时生效，供改造前的调用方沿用。 */
    collapsed?: boolean
    /** 平台名与副标题；传字符串时按旧的端名平铺显示。 */
    brand?: string | { title: string; subtitle?: string }
    /** 模块树：一级为模块，二级为页面；有权限的页面由调用方预先筛掉（`UI-P13`）。 */
    nav?: NavModule[]
    /** 是否渲染折叠按钮（`UI-P13`：折叠归侧栏底部）。 */
    collapsible?: boolean
    /** 用户区数据；不传不渲染用户区，另有 `user` 插槽可整体接管。 */
    user?: { name: string; roles: string[] }
  }>(),
  { brand: '货灵 WMS', menus: () => [], nav: () => [] },
)

const emit = defineEmits<{
  (e: 'logout'): void
  (e: 'navigate', path: string): void
}>()

/** 折叠状态在同浏览器内保留（`UI-P13`）：键固定，无存储值或读取异常时按视口决定初始值。 */
const STORAGE_KEY = 'wms.admin.sidebar.collapsed'

/**
 * 视口窄于此值时初始按收起渲染（`UI-P13`）：收起 64 加内容区下限 1200 合计 1264，
 * 1366×768 下不出横向滚动条；用户手动展开后允许横向滚动。
 */
const NARROW_VIEWPORT = 1400

/** 视口宽度判断，只在初始化时取一次，不随窗口缩放改状态（折叠态归用户控制）。 */
function collapsedByViewport(): boolean {
  return typeof window !== 'undefined' && window.innerWidth < NARROW_VIEWPORT
}

function readCollapsed(): boolean {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY)
    // 只有明确的 true／false 才算存储值；无值或其他脏值一律回落到视口判断
    if (stored === 'true' || stored === 'false') {
      return stored === 'true'
    }
    return collapsedByViewport()
  } catch {
    // 存储不可用（隐私模式等）同样按视口判断，与无存储值一致
    return collapsedByViewport()
  }
}

const innerCollapsed = ref(props.collapsible ? readCollapsed() : false)

/** 未开启折叠按钮时退回受控属性，保证货主端与单仓端的渲染与改造前一致。 */
const collapsed = computed(() => (props.collapsible ? innerCollapsed.value : Boolean(props.collapsed)))

function toggleCollapsed() {
  innerCollapsed.value = !innerCollapsed.value
  try {
    window.localStorage.setItem(STORAGE_KEY, String(innerCollapsed.value))
  } catch {
    // 写入失败只影响下次打开的默认状态，不影响本次折叠
  }
}

/** 传了模块树即启用新外壳；否则维持改造前的平铺菜单与顶栏端名。 */
const navMode = computed(() => props.nav.length > 0)

const brandTitle = computed(() => (typeof props.brand === 'string' ? props.brand : props.brand.title))
const brandSubtitle = computed(() => (typeof props.brand === 'string' ? '' : (props.brand.subtitle ?? '')))
/** 品牌方块里的字：取平台名首字，不引外部图形。 */
const brandInitial = computed(() => brandTitle.value.trim().charAt(0).toUpperCase())

/**
 * 展开的模块由当前路由推导（`UI-P13`）：同一时刻只展开当前页所在模块，
 * 不单独存状态；用户点模块头展开别的模块后，随路由变化重新对齐到当前页所在模块。
 */
const currentModule = computed(() =>
  props.nav.find((module) => module.children.some((page) => page.path === props.active)),
)
const openModule = ref(currentModule.value?.title ?? '')
// 路由或模块树变化时对齐到当前页所在模块；用户手动展开别的模块不受影响
watch([() => props.active, () => props.nav], () => {
  openModule.value = currentModule.value?.title ?? ''
})

function isOpen(module: NavModule): boolean {
  return openModule.value === module.title
}

function isCurrentModule(module: NavModule): boolean {
  return module.children.some((page) => page.path === props.active)
}

/** 展开态点模块头只展开／收起；收起态点模块图标进该模块首个有权限页面（`UI-P13`）。 */
function onModuleClick(module: NavModule) {
  if (collapsed.value) {
    const first = module.children[0]
    if (first && first.path !== props.active) {
      emit('navigate', first.path)
    }
    return
  }
  openModule.value = isOpen(module) ? '' : module.title
}

/** 点当前页不重复跳转，避免把页面已写进地址栏的筛选与分页冲掉（`UI-X04`）。 */
function onPageClick(path: string) {
  if (path !== props.active) {
    emit('navigate', path)
  }
}

/** 用户区：首字母方块取账号名首字。 */
const userInitial = computed(() => (props.user?.name.trim().charAt(0) ?? '').toUpperCase())
const userRoles = computed(() => props.user?.roles.join('、') || '—')

function onUserCommand(command: string) {
  if (command === 'logout') {
    emit('logout')
  }
}

/**
 * 使用说明抽屉（`UI-P12` 工具图标）：只写本外壳真实存在的行为与状态词释义，
 * 不编造快捷键与未实现的功能；词条取自 `@wms/dict`，不在此另写一份中文。
 */
const helpOpen = ref(false)
</script>

<template>
  <div class="app-shell">
    <header class="app-topbar">
      <div class="app-topbar-left">
        <!-- UI-P12：顶栏左侧品牌区，32×32 方块标记加平台名与副标题 -->
        <div v-if="navMode" class="app-brand-block">
          <span class="app-brand-mark">{{ brandInitial }}</span>
          <span class="app-brand-text">
            <span class="app-brand-title">{{ brandTitle }}</span>
            <span v-if="brandSubtitle" class="app-brand-subtitle">{{ brandSubtitle }}</span>
          </span>
        </div>
        <!-- 未传 nav 的端沿用改造前的顶栏端名 -->
        <div v-else class="app-brand">{{ brandTitle }}</div>
      </div>
      <div class="app-topbar-actions">
        <slot name="actions" />
        <!-- UI-P12：工具图标只放有真实动作的项，本次只有使用说明 -->
        <el-tooltip v-if="navMode" content="使用说明" placement="bottom" :show-after="300" :offset="8">
          <button type="button" class="app-icon-button" aria-label="使用说明" @click="helpOpen = true">
            <el-icon><QuestionFilled /></el-icon>
          </button>
        </el-tooltip>
        <!-- UI-P12：用户区「首字母方块 + 账号名 + 下拉箭头」，退出登录放在下拉内 -->
        <slot v-if="$slots.user" name="user" />
        <el-dropdown v-else-if="user" trigger="click" @command="onUserCommand">
          <span class="app-user">
            <span class="app-user-avatar">{{ userInitial }}</span>
            <span class="app-user-name">{{ user.name }}</span>
            <el-icon class="app-user-arrow"><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <!-- 当前角色只读展示，不在此处改角色 -->
              <el-dropdown-item disabled>{{ userRoles }}</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <div class="app-body">
      <aside class="app-sidebar" :class="{ 'is-collapsed': collapsed }">
        <!-- UI-P13：侧栏不放品牌区，自上而下为模块列表与底部工具条 -->
        <nav v-if="navMode" class="app-nav">
          <div v-for="module in nav" :key="module.title" class="app-nav-module">
            <!-- 收起态只显示模块图标，悬浮 300ms 显示模块名，点击进该模块首个页面 -->
            <el-tooltip
              :content="module.title"
              placement="right"
              :show-after="300"
              :disabled="!collapsed"
              :offset="8"
            >
              <button
                type="button"
                class="app-nav-module-head"
                :class="{ 'is-open': isOpen(module), 'is-current': isCurrentModule(module) }"
                :aria-label="module.title"
                :aria-expanded="collapsed ? undefined : isOpen(module)"
                @click="onModuleClick(module)"
              >
                <el-icon v-if="module.icon" class="app-nav-module-icon">
                  <component :is="module.icon" />
                </el-icon>
                <span v-if="!collapsed" class="app-nav-module-text">{{ module.title }}</span>
                <!-- UI-P13：右侧展开箭头，展开后翻转 -->
                <el-icon v-if="!collapsed" class="app-nav-arrow"><ArrowDown /></el-icon>
              </button>
            </el-tooltip>
            <!-- 二级页面：缩进 16、无图标、高 36，当前页用选中底 -->
            <div v-if="!collapsed && isOpen(module)" class="app-nav-pages">
              <button
                v-for="page in module.children"
                :key="page.path"
                type="button"
                class="app-nav-page"
                :class="{ 'is-active': page.path === active }"
                :aria-current="page.path === active ? 'page' : undefined"
                @click="onPageClick(page.path)"
              >
                {{ page.title }}
              </button>
            </div>
          </div>
        </nav>
        <!-- 未传 nav 的端：沿用改造前的平铺菜单，尺寸与改造前一致 -->
        <el-menu v-else :default-active="active" :collapse="collapsed" router class="app-menu">
          <el-tooltip
            v-for="item in menus"
            :key="item.path"
            :content="item.title"
            placement="right"
            :show-after="300"
            :disabled="!collapsed"
            :offset="8"
          >
            <el-menu-item :index="item.path">
              <span class="app-menu-text">{{ item.title }}</span>
            </el-menu-item>
          </el-tooltip>
        </el-menu>

        <!-- UI-P13：底部工具条固定帮助与折叠两项，收起态只留图标 -->
        <div v-if="navMode || collapsible" class="app-sidebar-tools" :class="{ 'is-collapsed': collapsed }">
          <el-tooltip v-if="navMode" content="使用说明" placement="right" :show-after="300" :offset="8">
            <button type="button" class="app-icon-button" aria-label="使用说明" @click="helpOpen = true">
              <el-icon><QuestionFilled /></el-icon>
            </button>
          </el-tooltip>
          <el-tooltip
            v-if="collapsible"
            :content="collapsed ? '展开侧栏' : '收起侧栏'"
            placement="right"
            :show-after="300"
            :offset="8"
          >
            <button
              type="button"
              class="app-icon-button"
              :aria-label="collapsed ? '展开侧栏' : '收起侧栏'"
              @click="toggleCollapsed"
            >
              <el-icon><component :is="collapsed ? Expand : Fold" /></el-icon>
            </button>
          </el-tooltip>
        </div>
        <!-- 底部行由端自己填：版本等信息不由外壳编造 -->
        <div v-if="$slots['sidebar-footer']" class="app-sidebar-footer">
          <slot name="sidebar-footer" />
        </div>
      </aside>

      <!-- UI-P04：内容区 1200~1440 居中，内边距 24；模块页签由调用方放在插槽最前 -->
      <main class="app-content">
        <slot />
      </main>
    </div>

    <!-- 使用说明：外壳自带唯一动作的说明，非详情类抽屉统一走 ActionDrawer（UI-C31） -->
    <ActionDrawer v-if="navMode" v-model="helpOpen" title="使用说明" :width="480">
      <section class="help-section">
        <h3 class="help-title">导航说明</h3>
        <ul class="help-list">
          <li class="help-item">侧栏按模块分组：点模块展开它下面的页面，同一时刻只展开当前页所在的模块。</li>
          <li class="help-item">内容区顶部的页签是当前模块内的页面切换，跨模块切换走侧栏。</li>
          <li class="help-item">侧栏底部可以收起侧栏；收起后点模块图标进入该模块第一个页面，鼠标停留可看模块名。</li>
        </ul>
      </section>
      <section class="help-section">
        <h3 class="help-title">状态标签释义</h3>
        <p class="help-text">账号状态</p>
        <ul class="help-list">
          <li v-for="(label, code) in accountStatusLabels" :key="code" class="help-item">
            {{ label }}<span class="help-code">{{ code }}</span>
          </li>
        </ul>
        <p class="help-text">商品档案状态</p>
        <ul class="help-list">
          <li v-for="(label, code) in archiveStatusLabels" :key="code" class="help-item">
            {{ label }}<span class="help-code">{{ code }}</span>
          </li>
        </ul>
      </section>
    </ActionDrawer>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  background: var(--el-bg-color-page);
}

/* UI-P04、UI-P12：顶栏高 56，分左右两组 */
.app-topbar {
  height: var(--wms-topbar-height);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--wms-page-padding);
  background: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.app-topbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

/* 未传 nav 的端：改造前的端名 */
.app-brand {
  font-size: 16px;
  font-weight: 600;
}

/* UI-P12：品牌区为方块标记加平台名与副标题 */
.app-brand-block {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.app-brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  border-radius: 4px;
  background-color: var(--el-color-primary);
  color: var(--el-color-white);
  font-size: 16px;
  font-weight: 600;
}

.app-brand-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

/* UI-T12：平台名取 16 档 */
.app-brand-title {
  font-size: 16px;
  font-weight: 600;
  line-height: 1.5;
}

/* UI-T12：副标题取辅助信息 12 档，不抢平台名 */
.app-brand-subtitle {
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

.app-topbar-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* 顶栏与侧栏共用的图标按钮：32×32，悬浮取浅填充（UI-T21） */
.app-icon-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--el-text-color-regular);
  font-size: 16px;
  cursor: pointer;
}

.app-icon-button:hover {
  background-color: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
}

.app-user {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

/* UI-T21：首字母方块取只读底，不用强调色 */
.app-user-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 4px;
  background-color: var(--el-fill-color);
  color: var(--el-text-color-primary);
  font-size: 12px;
}

.app-user-name {
  font-size: 14px;
  line-height: 1.5;
}

.app-user-arrow {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.app-body {
  display: flex;
  min-height: calc(100vh - var(--wms-topbar-height));
}

/*
 * UI-P13：展开 200、收起 64 都是定值，不允许被内容区挤压。
 * 视口容不下「侧栏 + 内容区下限 1200」（例如 1366 下手动展开）时按设计回落到横向滚动。
 */
.app-sidebar {
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  width: var(--wms-sidebar-width);
  background: var(--el-bg-color);
  border-right: 1px solid var(--el-border-color-lighter);
  transition: width 0.2s;
}

.app-sidebar.is-collapsed {
  width: var(--wms-sidebar-width-collapsed);
}

/* 模块列表：滚动区，按钮化行不再继承 el-menu 的默认样式 */
.app-nav {
  flex: 1;
  min-height: 0;
  padding: 8px;
  overflow-y: auto;
}

.app-nav-module {
  margin-bottom: 4px;
}

/* UI-P13：一级模块高 40，图标 16、文字 14、间距 8 */
.app-nav-module-head {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 40px;
  padding: 0 8px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--el-text-color-regular);
  font-size: 14px;
  line-height: 1.5;
  text-align: left;
  cursor: pointer;
}

.app-nav-module-head:hover {
  background-color: var(--el-fill-color-light);
}

.app-nav-module-icon {
  flex-shrink: 0;
  font-size: 16px;
}

.app-nav-module-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* UI-T19：图标只取 16／20／24 三档，箭头取 16 */
.app-nav-arrow {
  flex-shrink: 0;
  font-size: 16px;
  color: var(--el-text-color-secondary);
  transition: transform 0.2s;
}

.app-nav-module-head.is-open .app-nav-arrow {
  transform: rotate(180deg);
}

/* UI-P13：二级页面缩进 16、无图标、高 36，当前页用选中底，不加左侧色条 */
.app-nav-page {
  display: flex;
  align-items: center;
  width: 100%;
  height: 36px;
  padding: 0 8px 0 32px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--el-text-color-regular);
  font-size: 14px;
  line-height: 1.5;
  text-align: left;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  cursor: pointer;
}

.app-nav-page:hover {
  background-color: var(--el-fill-color-light);
}

/* UI-T21：当前项用选中底 */
.app-nav-page.is-active {
  background-color: var(--el-color-primary-light-9);
  color: var(--el-text-color-primary);
}

/* 收起态：只显示模块图标，当前页所在模块用选中底标出 */
.app-sidebar.is-collapsed .app-nav-module-head {
  justify-content: center;
  padding: 0;
}

.app-sidebar.is-collapsed .app-nav-module-head.is-current {
  background-color: var(--el-color-primary-light-9);
  color: var(--el-text-color-primary);
}

/* UI-P13：底部工具条，收起态竖排只留图标 */
.app-sidebar-tools {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  padding: 8px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.app-sidebar-tools.is-collapsed {
  flex-direction: column;
}

.app-menu {
  flex: 1;
  border-right: none;
  overflow-y: auto;
}

.app-sidebar-footer {
  padding: 12px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

/* UI-C31：说明抽屉正文分区，标题 14/600，正文 13 辅助色 */
.help-section + .help-section {
  margin-top: 16px;
}

.help-title {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
}

.help-list {
  margin: 0;
  padding-left: 16px;
}

.help-item {
  font-size: 13px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

.help-text {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

/* 枚举值后缀：只做对照，不参与文案 */
.help-code {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* UI-P04：内容区 1200~1440 居中，内边距 24 */
.app-content {
  flex: 1;
  min-width: var(--wms-content-min-width);
  max-width: var(--wms-content-max-width);
  margin: 0 auto;
  padding: var(--wms-page-padding);
  box-sizing: border-box;
}
</style>
