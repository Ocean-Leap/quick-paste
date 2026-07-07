/// <reference lib="webworker" />
// quick-paste service worker (built via vite-plugin-pwa injectManifest).
// Precaches the app shell, handles web push + notification clicks, and stashes
// Web Share Target POSTs for the app to pick up. Nothing under /api or
// /health is ever cached or intercepted (privacy, NFR-6).

import { clientsClaim } from 'workbox-core';
import {
  cleanupOutdatedCaches,
  createHandlerBoundToURL,
  precacheAndRoute,
} from 'workbox-precaching';
import { NavigationRoute, registerRoute } from 'workbox-routing';
import { putShare } from './lib/shareStore';

declare let self: ServiceWorkerGlobalScope & {
  __WB_MANIFEST: Parameters<typeof precacheAndRoute>[0];
};

// Activate updates immediately (registerType: 'autoUpdate').
void self.skipWaiting();
clientsClaim();

precacheAndRoute(self.__WB_MANIFEST);
cleanupOutdatedCaches();

// SPA navigation fallback for everything except the API and health endpoints.
registerRoute(
  new NavigationRoute(createHandlerBoundToURL('/index.html'), {
    denylist: [/^\/api\//, /^\/health/],
  }),
);

interface PushPayload {
  title?: string;
  body?: string;
  channelId?: string;
  clipId?: string;
  type?: string;
}

self.addEventListener('push', (event) => {
  let payload: PushPayload = {};
  try {
    payload = (event.data?.json() ?? {}) as PushPayload;
  } catch {
    // Malformed payload — show a generic notification.
  }
  event.waitUntil(
    self.registration.showNotification(payload.title || 'quick-paste', {
      body: payload.body,
      // Android Chrome ignores SVG notification icons (renders blank); use the
      // precached PNG for both the icon and the small status-bar badge.
      icon: '/icon-192.png',
      badge: '/icon-192.png',
      data: { channelId: payload.channelId },
      tag: payload.clipId,
    }),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const data = event.notification.data as { channelId?: string } | undefined;
  const channelId = data?.channelId;
  event.waitUntil(
    (async () => {
      const clients = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
      const client = clients[0];
      if (client) {
        await client.focus();
        client.postMessage({ type: 'open-channel', channelId });
        return;
      }
      await self.clients.openWindow(
        channelId ? `/?channel=${encodeURIComponent(channelId)}` : '/',
      );
    })(),
  );
});

// Web Share Target: stash the shared payload and bounce to the app, which
// reads it from IndexedDB on startup (see Shell).
self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'POST') return;
  const url = new URL(event.request.url);
  if (url.pathname !== '/share-target') return;
  event.respondWith(
    (async () => {
      const form = await event.request.formData();
      const field = (name: string): string => {
        const value = form.get(name);
        return typeof value === 'string' ? value : '';
      };
      const files = form.getAll('files').filter((entry): entry is File => entry instanceof File);
      await putShare({ title: field('title'), text: field('text'), url: field('url'), files });
      return Response.redirect('/?share=pending', 303);
    })(),
  );
});
