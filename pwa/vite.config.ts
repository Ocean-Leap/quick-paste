import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';
import { VitePWA } from 'vite-plugin-pwa';

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      // Custom service worker (src/sw.ts): precache + navigation fallback,
      // web push, notification clicks and the Web Share Target intake.
      strategies: 'injectManifest',
      srcDir: 'src',
      filename: 'sw.ts',
      registerType: 'autoUpdate',
      includeAssets: ['icon.svg'],
      manifest: {
        name: 'quick-paste',
        short_name: 'quick-paste',
        description: 'Shared ephemeral clipboard for your devices',
        display: 'standalone',
        start_url: '/',
        theme_color: '#3E2FBB',
        background_color: '#FAFAF7',
        icons: [
          // PNGs first: desktop Chrome/Edge and older Android launchers prefer
          // (or require) raster icons; the full-bleed art keeps the glyph inside
          // the maskable safe zone.
          { src: '/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any maskable' },
          { src: '/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any maskable' },
          { src: '/icon.svg', sizes: '192x192', type: 'image/svg+xml', purpose: 'any maskable' },
          { src: '/icon.svg', sizes: '512x512', type: 'image/svg+xml', purpose: 'any maskable' },
        ],
        share_target: {
          action: '/share-target',
          method: 'POST',
          enctype: 'multipart/form-data',
          params: {
            title: 'title',
            text: 'text',
            url: 'url',
            files: [{ name: 'files', accept: ['*/*'] }],
          },
        },
      },
      injectManifest: {
        // Precache the app shell only; clip/file content must never be cached (NFR-6).
        globPatterns: ['**/*.{js,css,html,svg,woff2}'],
      },
    }),
  ],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', ws: true, changeOrigin: false },
      '/health': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
});
