import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The UI is a separate deployable. In development it proxies /admin/api to a locally running
// service so the session cookie and the CSRF cookie are same-origin, exactly as nginx does in
// the Compose stack and on Kubernetes. Point ADMIN_API_TARGET elsewhere to develop against
// another instance.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/admin/api': { target: process.env.ADMIN_API_TARGET ?? 'http://localhost:8080', changeOrigin: false } },
  },
  build: { outDir: 'dist', sourcemap: false },
  test: { environment: 'node', include: ['src/**/*.test.ts'] },
});
