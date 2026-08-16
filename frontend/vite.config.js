import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

const rootDir = fileURLToPath(new URL('.', import.meta.url))

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, rootDir, '')
  // 单端口：业务 + 登录 + MCP + Agent 全部代理到 8088
  const proxyTarget = env.VITE_DEV_PROXY_TARGET || 'http://127.0.0.1:8088'

  return {
    root: rootDir,
    plugins: [vue()],
    build: {
      sourcemap: false,
      chunkSizeWarningLimit: 720,
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (!id.includes('node_modules')) {
              return undefined
            }
            // Element Plus 按组件拆 chunk 会与 teleport/affix/dialog 形成循环依赖，
            // 浏览器报 Cannot access 'x' before initialization；整库打一个包即可。
            if (id.includes('element-plus') || id.includes('@element-plus')) return 'vendor-element-plus'
            if (id.includes('echarts')) return 'vendor-echarts'
            if (id.includes('zrender')) return 'vendor-zrender'
            if (id.includes('html2canvas')) return 'vendor-html2canvas'
            if (id.includes('jspdf')) return 'vendor-jspdf'
            if (id.includes('markdown-it')) return 'vendor-markdown'
            if (id.includes('vue/') || id.includes('@vue/')) return 'vendor-vue'
            if (id.includes('axios')) return 'vendor-axios'
            if (id.includes('lodash')) return 'vendor-lodash'
            return 'vendor-misc'
          }
        }
      }
    },
    server: {
      port: 3000,
      strictPort: false,
      proxy: {
        '/award-log': {
          target: proxyTarget,
          changeOrigin: true,
          ws: true
        },
        '/api': {
          target: proxyTarget,
          changeOrigin: true,
          ws: true,
          rewrite: (path) => path.replace(/^\/api/, '/award-log/api')
        },
        '/mcp': {
          target: proxyTarget,
          changeOrigin: true,
          ws: true,
          rewrite: (path) => path.replace(/^\/mcp/, '/award-log/api/mcp')
        }
      }
    }
  }
})
