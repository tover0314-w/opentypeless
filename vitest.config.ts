import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

const appVersion = process.env.npm_package_version || '0.1.0'

export default defineConfig({
  plugins: [react()],
  define: {
    'import.meta.env.VITE_APP_VERSION': JSON.stringify(`v${appVersion}`),
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['src/test-setup.ts'],
  },
})
