import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';


export default defineConfig({
  define: {
    // Меняй при важных правках UI — видно в шапке формы задачи (проверка деплоя Render).
    __UI_BUILD_TAG__: JSON.stringify('proxy-ip-2026-03-22'),
  },
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:3000',
        changeOrigin: true,
      },
    },
  },
});

