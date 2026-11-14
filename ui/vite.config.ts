import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev server proxies /api to the Spring Boot service; `npm run build` output (dist/)
// is also served by the service itself on :8080.
const api = process.env.CARDDEMO_API ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy: { '/api': api } },
  preview: { port: 4173, proxy: { '/api': api } },
});
