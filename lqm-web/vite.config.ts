import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig(({ mode }) => {
  // `npm run dev:ui` (mode devui) aponta para uma API sem OIDC em outra porta: dá para percorrer as telas sem login.
  const env = loadEnv(mode, process.cwd(), 'LQM_');
  return {
    plugins: [react(), tailwindcss()],
    server: {
      port: Number(env.LQM_DEV_PORT ?? 5173),
      // A API local sobe pelo deploy/docker-compose.yml.
      proxy: { '/api': { target: env.LQM_API_PROXY ?? 'http://localhost:8080', changeOrigin: true } },
    },
    build: { outDir: 'dist', sourcemap: true },
  };
});
