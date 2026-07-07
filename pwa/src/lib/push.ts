import { ApiError, createPushSubscription, deletePushSubscription } from '../api/client';

const STORAGE_KEY = 'qp.pushSubId';

export type PushState = 'unsupported' | 'blocked' | 'disabled' | 'enabled';

export function pushSupported(): boolean {
  return 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
}

/** Decodes a URL-safe base64 VAPID key into the bytes subscribe() expects. */
function urlBase64ToUint8Array(base64: string): Uint8Array {
  const padding = '='.repeat((4 - (base64.length % 4)) % 4);
  const normalized = (base64 + padding).replace(/-/g, '+').replace(/_/g, '/');
  const raw = atob(normalized);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i += 1) bytes[i] = raw.charCodeAt(i);
  return bytes;
}

function storedSubscriptionId(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

/** Current opt-in state, without ever prompting the user (SPEC §10.7). */
export async function getPushState(): Promise<PushState> {
  if (!pushSupported()) return 'unsupported';
  if (Notification.permission === 'denied') return 'blocked';
  const registration = await navigator.serviceWorker.getRegistration();
  const subscription = await registration?.pushManager.getSubscription();
  return subscription ? 'enabled' : 'disabled';
}

/**
 * Asks for notification permission (user gesture), subscribes with the VAPID
 * key and registers the subscription with the server.
 */
export async function enablePush(vapidPublicKey: string, device: string): Promise<PushState> {
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') return permission === 'denied' ? 'blocked' : 'disabled';
  const registration = await navigator.serviceWorker.ready;
  const subscription = await registration.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: urlBase64ToUint8Array(vapidPublicKey),
  });
  const json = subscription.toJSON();
  const p256dh = json.keys?.p256dh;
  const auth = json.keys?.auth;
  if (!json.endpoint || !p256dh || !auth) {
    await subscription.unsubscribe();
    throw new Error('The browser returned an incomplete push subscription.');
  }
  try {
    const created = await createPushSubscription({
      endpoint: json.endpoint,
      keys: { p256dh, auth },
      device,
    });
    try {
      localStorage.setItem(STORAGE_KEY, created.id);
    } catch {
      // Best effort only — disable will still unsubscribe locally.
    }
  } catch (err) {
    await subscription.unsubscribe();
    throw err;
  }
  return 'enabled';
}

/** Unsubscribes locally and removes the server-side subscription. */
export async function disablePush(): Promise<PushState> {
  const registration = await navigator.serviceWorker.getRegistration();
  const subscription = await registration?.pushManager.getSubscription();
  if (subscription) await subscription.unsubscribe();
  const id = storedSubscriptionId();
  if (id) {
    try {
      await deletePushSubscription(id);
    } catch (err) {
      // Already gone server-side — that is the state we want.
      if (!(err instanceof ApiError && err.status === 404)) throw err;
    }
  }
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Best effort only.
  }
  return 'disabled';
}
