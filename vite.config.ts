import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// @ts-expect-error process is a nodejs global
const host = process.env.TAURI_DEV_HOST

export default defineConfig(async ({ mode }) => {
  const environment = loadEnv(mode, '.', '')
  const apiBase = environment.VITE_API_BASE_URL ?? 'https://www.opentypeless.com'
  const target = new URL(apiBase)
  if (target.protocol !== 'https:' && target.protocol !== 'http:') {
    throw new Error('VITE_API_BASE_URL must use HTTP or HTTPS')
  }
  return {
    plugins: [react(), tailwindcss()],
    clearScreen: false,
    server: {
      port: 1420,
      strictPort: true,
      host: host || false,
      proxy: {
        '^/__opentypeless_cloud/api/': {
          target: target.origin,
          changeOrigin: true,
          cookieDomainRewrite: '',
          rewrite: (path) =>
            `${target.pathname.replace(/\/$/, '')}${path.replace(/^\/__opentypeless_cloud/, '')}`,
          configure: (proxy) => {
            proxy.on('proxyReq', (request) => {
              request.setHeader('Origin', 'tauri://localhost')
            })
          },
        },
      },
      hmr: host
        ? {
            protocol: 'ws',
            host,
            port: 1421,
          }
        : undefined,
      watch: {
        ignored: ['**/src-tauri/**'],
      },
    },
  }
})
