import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  base: './',
  build: {
    cssTarget: 'safari14',
    outDir: 'onboarding-preview-dist',
    rollupOptions: {
      input: 'onboarding-preview.html',
    },
  },
  preview: {
    host: '127.0.0.1',
    port: 1439,
    strictPort: true,
  },
})
