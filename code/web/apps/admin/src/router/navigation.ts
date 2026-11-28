/**
 * 模块表、模块树与模块页签的唯一计算点，对应 12a 的 `UI-P13`、`UI-P16`。
 *
 * 路由元数据只声明所属模块（`meta.group`），模块图标与模块顺序登记在本文件，
 * 避免同一模块的多个页面各写一遍图标。菜单与页签的成员仍由调用方按
 * 「路由表 ∩ 权限集」筛选（`canVisit`），本文件不做任何权限判定。
 */
import type { Component } from 'vue'
import { Box, Grid, Odometer, User } from '@element-plus/icons-vue'

/** 运营端落地页：工作台功能入口与模块推导都以它为参照，不在多处写路径字面量。 */
export const HOME_PATH = '/home'

/** 模块顺序固定（`UI-P13`）：概览、主数据、权限与审计、其他，不随路由注册顺序变化。 */
const GROUP_ORDER = ['概览', '主数据', '权限与审计']

/** 未声明模块的页面归入「其他」；该模块为空时不渲染。 */
const DEFAULT_GROUP = '其他'

/**
 * 模块图标固定表，取值逐字取 `design.md`「模块与路由元数据」表。
 * 「其他」是兜底模块，设计表未给图标，取通用方格图标，不给页面另造模块。
 */
const GROUP_ICONS: Record<string, Component> = {
  概览: Odometer,
  主数据: Box,
  权限与审计: User,
}

const FALLBACK_ICON: Component = Grid

export interface NavPage {
  path: string
  title: string
}

export interface NavModule {
  title: string
  icon: Component
  children: NavPage[]
}

/** 路由表投影：调用方已按 `meta.menu` 与权限集筛过，本文件只负责归并。 */
export interface RoutePage {
  path: string
  title: string
  group?: string
}

function groupTitle(group?: string): string {
  return group?.trim() ? group.trim() : DEFAULT_GROUP
}

function rank(title: string): number {
  const index = GROUP_ORDER.indexOf(title)
  return index === -1 ? GROUP_ORDER.length : index
}

/** 模块图标（`design.md`「模块与路由元数据」）：未登记的模块取兜底图标。 */
export function moduleIcon(group?: string): Component {
  return GROUP_ICONS[groupTitle(group)] ?? FALLBACK_ICON
}

/**
 * 模块树：按模块归并页面，模块间顺序固定，模块内顺序沿用路由注册顺序（与侧栏二级一致）。
 * 空模块不出现；无权限页面已由调用方过滤，此处不会回流（`UI-X06`）。
 */
export function buildNav(pages: RoutePage[]): NavModule[] {
  const buckets = new Map<string, NavModule>()
  for (const page of pages) {
    const title = groupTitle(page.group)
    const bucket = buckets.get(title) ?? { title, icon: moduleIcon(title), children: [] }
    bucket.children.push({ path: page.path, title: page.title })
    buckets.set(title, bucket)
  }
  // Array#sort 稳定：同档模块保持首次出现的顺序
  return [...buckets.values()].sort((a, b) => rank(a.title) - rank(b.title))
}

/** 当前页所在模块；当前页不属于任何模块时返回 `undefined`（如无权访问页）。 */
export function moduleOf(nav: NavModule[], path: string): NavModule | undefined {
  return nav.find((module) => module.children.some((page) => page.path === path))
}

/** 模块页签 = 当前页所在模块的页面；单页模块由 `ModuleTabs` 决定不渲染（`UI-P16`）。 */
export function tabsOf(nav: NavModule[], path: string): NavPage[] {
  return moduleOf(nav, path)?.children ?? []
}
