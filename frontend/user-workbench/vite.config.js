import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: { proxy: { '/api': 'http://127.0.0.1:8088' } },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          mui: ['@mui/material', '@mui/icons-material', '@emotion/react', '@emotion/styled'],
          flow: ['@xyflow/react', '@dagrejs/dagre'],
          query: ['@tanstack/react-query']
        }
      }
    }
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.js',
    css: false,
    // 并行跑全量用例时，交互较重的表单用例在慢机上会超过 vitest 默认 5s 上限而误报超时；
    // 这里只放宽等待预算，断言、跳过与覆盖范围都保持不变。
    testTimeout: 20000,
    hookTimeout: 20000
  }
});
