import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

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
    port: 5173,
    proxy: {
      '/hub/api': {
        // 本地联调指向当前在跑的运营端实例；正式端口为 8081。
        target: process.env.WMS_HUB_TARGET ?? 'http://localhost:8094',
        changeOrigin: true,
      },
    },
  },
})
