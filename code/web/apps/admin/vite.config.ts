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
      // 运营后台本地联调同时存在两套后端前缀，二者都转到同一个后端实例，
      // 因此共用同一个 target，不设 rewrite（后端本身就按这两个前缀暴露）。
      //
      // 1) /hub/api/**：身份与登录相关接口，后端 AuthController 的
      //    @RequestMapping("/hub/api/auth") 即在此前缀下。
      // 2) /api/hub/**：商品档案与受理查询等业务接口，按接口契约
      //    （如 API-007 GET /api/hub/archives）固定使用 /api/hub 前缀，
      //    与后端 HubArchiveController 的 @RequestMapping("/api/hub/archives") 对齐。
      //
      // 两套前缀不可合并：路径字面量不同，且 /hub/api 不能覆盖 /api/hub，
      // 漏配任意一条都会命中开发服务器的 SPA 回退（返回 text/html）而非 JSON。
      '/hub/api': {
        // 本地联调指向当前在跑的运营端实例；正式端口为 8081。
        target: process.env.WMS_HUB_TARGET ?? 'http://localhost:8094',
        changeOrigin: true,
      },
      '/api/hub': {
        // 业务接口前缀，与 /hub/api 指向同一后端实例。
        target: process.env.WMS_HUB_TARGET ?? 'http://localhost:8094',
        changeOrigin: true,
      },
    },
  },
})
