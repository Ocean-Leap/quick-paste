// Service worker: context menus, the send-clipboard command, and the offscreen
// clipboard bridge. All captures go to the configured default channel.

import { ApiError, QuickPasteClient, errorMessage } from './lib/api';
import { getSettings, isConfigured, type Settings } from './lib/storage';

const MENU_SELECTION = 'qp-send-selection';
const MENU_LINK = 'qp-send-link';
const MENU_IMAGE = 'qp-send-image';

chrome.runtime.onInstalled.addListener(() => {
  // removeAll first so re-running on update can't throw duplicate-id errors.
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({
      id: MENU_SELECTION,
      title: 'Send selection to quick-paste',
      contexts: ['selection'],
    });
    chrome.contextMenus.create({
      id: MENU_LINK,
      title: 'Send link to quick-paste',
      contexts: ['link'],
    });
    chrome.contextMenus.create({
      id: MENU_IMAGE,
      title: 'Send image to quick-paste',
      contexts: ['image'],
    });
  });
});

chrome.contextMenus.onClicked.addListener((info, tab) => {
  void handleMenu(info, tab);
});

chrome.commands.onCommand.addListener((command) => {
  if (command === 'send-clipboard') {
    void handleSendClipboard();
  }
});

async function handleMenu(
  info: chrome.contextMenus.OnClickData,
  tab: chrome.tabs.Tab | undefined,
): Promise<void> {
  try {
    const { client, settings } = await requireClient();
    if (info.menuItemId === MENU_SELECTION && info.selectionText) {
      await client.createTextClip(settings.defaultChannelId, info.selectionText, settings.device);
      notify('Sent selection to quick-paste');
    } else if (info.menuItemId === MENU_LINK && info.linkUrl) {
      await client.createTextClip(settings.defaultChannelId, info.linkUrl, settings.device);
      notify('Sent link to quick-paste');
    } else if (info.menuItemId === MENU_IMAGE && info.srcUrl) {
      await sendImage(client, settings, info.srcUrl, tab?.id);
    }
  } catch (err) {
    notifyError(err);
  }
}

async function sendImage(
  client: QuickPasteClient,
  settings: Settings,
  srcUrl: string,
  tabId: number | undefined,
): Promise<void> {
  const config = await client.config();
  // Fetch the image bytes IN THE PAGE (activeTab), not from the service worker:
  // the SW only holds host permission for the quick-paste origin, so a direct
  // fetch of a third-party image origin is CORS-blocked.
  const blob = await fetchImageInPage(tabId, srcUrl);
  if (blob.size > config.simpleUploadMb * 1024 * 1024) {
    throw new Error(`Image is larger than ${config.simpleUploadMb} MB — send it from the app.`);
  }
  const name = fileNameFromUrl(srcUrl, blob.type);
  const uploaded = await client.uploadFile(settings.defaultChannelId, blob, name);
  await client.createFileClip(settings.defaultChannelId, uploaded.fileId, settings.device);
  notify('Sent image to quick-paste');
}

/**
 * Runs a fetch inside the page (same context that already loaded the image),
 * returning a data URL. Works for same-origin and CORS-permitted images; for a
 * hard cross-origin block it throws a clear message pointing to the app.
 */
async function fetchImageInPage(tabId: number | undefined, srcUrl: string): Promise<Blob> {
  if (tabId === undefined) {
    throw new Error('Could not read the image from this page.');
  }
  const [result] = await chrome.scripting.executeScript({
    target: { tabId },
    func: async (url: string): Promise<string> => {
      const res = await fetch(url);
      if (!res.ok) throw new Error(String(res.status));
      const blob = await res.blob();
      return await new Promise<string>((resolve, reject) => {
        const reader = new FileReader();
        reader.onloadend = () => resolve(reader.result as string);
        reader.onerror = () => reject(new Error('read failed'));
        reader.readAsDataURL(blob);
      });
    },
    args: [srcUrl],
  });
  const dataUrl = result?.result as string | undefined;
  if (!dataUrl) {
    throw new Error("Couldn't grab that image — open it in a tab, or attach it from the app.");
  }
  const res = await fetch(dataUrl); // data: URL, no network
  return res.blob();
}

async function handleSendClipboard(): Promise<void> {
  try {
    const { client, settings } = await requireClient();
    const text = await readClipboardViaOffscreen();
    if (!text.trim()) {
      notify('Clipboard is empty');
      return;
    }
    await client.createTextClip(settings.defaultChannelId, text, settings.device);
    notify('Sent clipboard to quick-paste');
  } catch (err) {
    notifyError(err);
  }
}

async function requireClient(): Promise<{ client: QuickPasteClient; settings: Settings }> {
  const settings = await getSettings();
  if (!isConfigured(settings) || !settings.defaultChannelId) {
    throw new Error('Set up quick-paste first (click the toolbar icon).');
  }
  return { client: new QuickPasteClient(settings.serverUrl, settings.token), settings };
}

// ---- offscreen clipboard bridge ----

const OFFSCREEN_PATH = 'offscreen.html';
let offscreenCreating: Promise<void> | null = null;

async function readClipboardViaOffscreen(): Promise<string> {
  await ensureOffscreen();
  const response = (await sendToOffscreenWithRetry()) as { ok: boolean; text?: string };
  if (!response?.ok) {
    throw new Error('Clipboard read was blocked.');
  }
  return response.text ?? '';
}

/** The offscreen document's onMessage listener may not be registered the instant
 *  createDocument resolves; retry briefly on "receiving end does not exist". */
async function sendToOffscreenWithRetry(): Promise<unknown> {
  const message = { target: 'offscreen', action: 'read-clipboard' };
  for (let attempt = 0; attempt < 5; attempt++) {
    try {
      return await chrome.runtime.sendMessage(message);
    } catch (err) {
      if (attempt === 4 || !String(err).includes('receiving end does not exist')) {
        throw err;
      }
      await delay(60);
    }
  }
  return undefined;
}

/** Create-once: concurrent triggers share one creation promise so the second
 *  caller never issues a duplicate createDocument (which would reject). */
async function ensureOffscreen(): Promise<void> {
  const existing = await chrome.runtime.getContexts({
    contextTypes: [chrome.runtime.ContextType.OFFSCREEN_DOCUMENT],
  });
  if (existing.length > 0) {
    return;
  }
  if (!offscreenCreating) {
    offscreenCreating = chrome.offscreen
      .createDocument({
        url: OFFSCREEN_PATH,
        reasons: [chrome.offscreen.Reason.CLIPBOARD],
        justification: 'Read the clipboard to send it to quick-paste.',
      })
      .catch((err: unknown) => {
        // A racing creation may already have made it; tolerate that, rethrow else.
        if (!String(err).includes('single offscreen document')) {
          throw err;
        }
      })
      .finally(() => {
        offscreenCreating = null;
      });
  }
  await offscreenCreating;
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// ---- notifications ----

function notify(message: string): void {
  chrome.notifications.create({
    type: 'basic',
    iconUrl: 'public/icon-128.png',
    title: 'quick-paste',
    message,
  });
}

function notifyError(err: unknown): void {
  const message = err instanceof ApiError && err.status === 401
    ? 'Your API token was rejected — update it in settings.'
    : errorMessage(err);
  notify(message);
}

function fileNameFromUrl(url: string, mime: string): string {
  try {
    const path = new URL(url).pathname;
    const base = path.substring(path.lastIndexOf('/') + 1);
    if (base && base.includes('.')) {
      return decodeURIComponent(base);
    }
  } catch {
    // data: URLs and the like fall through to a generated name
  }
  const ext = mime.split('/')[1]?.split('+')[0] ?? 'bin';
  return `image.${ext}`;
}
