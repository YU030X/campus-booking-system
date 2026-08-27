import { fileURLToPath } from 'node:url';
import { defineConfig } from '../../../booking-web/node_modules/vite/dist/node/index.js';
import vue from '../../../booking-web/node_modules/@vitejs/plugin-vue/dist/index.js';

const root = fileURLToPath(new URL('../../../booking-web/', import.meta.url));

export default defineConfig({
  root,
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 4173,
    strictPort: true,
    proxy: {
      '/api/v1': {
        target: 'http://127.0.0.1:18080',
        changeOrigin: false,
      },
    },
  },
});
