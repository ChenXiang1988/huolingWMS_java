import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 货主端产物独立部署（`01`「前端三端共享代码包，产物按部署位置分开」）：
 * 不打包运营端与单仓端的路由、代码与静态资源，只依赖 `packages/` 的共享包。
 * 接口前缀按契约「基础路径与端分组」为 `/api/owner`，本地开发代理到 open 单元。
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
    port: 5174,
    proxy: {
      '/api/owner': {
        target: process.env.WMS_OPEN_TARGET ?? 'http://localhost:8083',
        changeOrigin: true,
      },
    },
  },
})
