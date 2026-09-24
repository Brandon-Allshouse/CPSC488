import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';

// Content-Security-Policy for the built site: the browser will only run scripts and styles that
// come from our own origin, so injected <script> tags or inline event handlers can't execute.
// Only applied to production builds, because the dev server needs inline scripts for hot reload.
// When YouTube embeds are added, extend frame-src / img-src with the YouTube domains.
const CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'self' data:",
  "connect-src 'self'",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
].join('; ');

function contentSecurityPolicy(): Plugin {
  return {
    name: 'content-security-policy',
    apply: 'build',
    transformIndexHtml: () => [
      { tag: 'meta', attrs: { 'http-equiv': 'Content-Security-Policy', content: CSP }, injectTo: 'head-prepend' },
    ],
  };
}

// During `npm run dev`, the browser loads everything from http://localhost:5173, and Vite forwards
// any /api/... request to the Javalin backend. Since the browser only sees one origin, cookies
// work and no CORS setup is needed. A production deployment needs a web server that does the same
// forwarding. The backend is the only thing that talks to the database.
//
// Both settings below are set by docker-compose.yml; the defaults are for running without Docker.
// Where the backend is: "http://backend:7070" inside Docker, localhost otherwise.
const apiProxyTarget = process.env.API_PROXY_TARGET ?? 'http://localhost:7070';
// Docker on Windows doesn't pass file-change events into containers, so Vite must poll there.
const usePolling = process.env.VITE_USE_POLLING === 'true';

export default defineConfig({
  plugins: [react(), contentSecurityPolicy()],
  server: {
    port: 5173,
    // Fail instead of silently switching to another port, because the backend's ALLOWED_ORIGINS
    // expects exactly http://localhost:5173.
    strictPort: true,
    watch: usePolling ? { usePolling: true, interval: 300 } : undefined,
    proxy: {
      '/api': apiProxyTarget,
    },
  },
});
