import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 单仓端产物必须能脱离 Hub 与 Open 独立部署（`01`「单仓端产物必须能脱离 Hub 与 Open 独立部署」）：
 * 不打包另两端的路由与代码，不引用另两端的静态资源与接口域名。
 * 接口前缀按契约「基础路径与端分组」为 `/api/site`，本地开发代理到本仓 site 单元；
 * 单仓端会话的换取与续期按契约「单仓端会话与权限」走认证入口 `/api/hub/auth/**`（API-018、API-019），
 * 只代理这两个契约路径，不复用运营端页面与资源。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      '@wms/design': fileURLToPath(new URL('../../packages/design/src/index.ts', import.meta.url)),
      '@wms/request': fileURLToPath(new URL('../../packages/request/src/index.ts', import.meta.url)),
      '@wms/dict': fileURLToPath(new URL('../../packages/dict/src/index.ts', import.meta.url)),
    },
  },
  server: {
    port: 5175,
    proxy: {
      '/api/site': {
        target: process.env.WMS_SITE_TARGET ?? 'http://localhost:8082',
        changeOrigin: true,
      },
      '/api/hub/auth': {
        target: process.env.WMS_HUB_TARGET ?? 'http://localhost:8094',
        changeOrigin: true,
      },
    },
  },
})
