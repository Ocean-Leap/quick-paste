// Popup: channel switcher, unified compose (text + staged files), and recent
// history with copy/download and image thumbnails.

import { ApiError, QuickPasteClient, errorMessage } from './lib/api';
import { getSettings, isConfigured, setSettings } from './lib/storage';
import type { ChannelDto, ClientConfigDto, ClipDto } from './lib/types';

const $ = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;

const channelSelect = $<HTMLSelectElement>('channel');
const composer = $<HTMLElement>('composer');
const textInput = $<HTMLTextAreaElement>('text');
const attachmentsEl = $<HTMLDivElement>('attachments');
const sendBtn = $<HTMLButtonElement>('send');
const attachBtn = $<HTMLButtonElement>('attach');
const fileInput = $<HTMLInputElement>('file');
const streamEl = $<HTMLDivElement>('stream');
const toastEl = $<HTMLDivElement>('toast');

interface Staged {
  file: File;
  url?: string; // one object URL per image chip, created once and revoked on removal
}

let client: QuickPasteClient | null = null;
let config: ClientConfigDto | null = null;
let channelId = '';
let staged: Staged[] = [];
let sending = false;
const historyUrls: string[] = []; // thumbnail object URLs, revoked on popup unload

$('settings').addEventListener('click', () => chrome.runtime.openOptionsPage());

async function init(): Promise<void> {
  const settings = await getSettings();
  if (!isConfigured(settings)) {
    renderState('Connect quick-paste to get started.', 'Open settings', () =>
      chrome.runtime.openOptionsPage(),
    );
    return;
  }
  client = new QuickPasteClient(settings.serverUrl, settings.token);
  try {
    const [me, cfg] = await Promise.all([client.me(), client.config()]);
    config = cfg;
    setupChannels(me.channels, settings.defaultChannelId);
    composer.hidden = false;
    channelSelect.hidden = false;
    await loadHistory();
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      renderState('Your API token was rejected.', 'Update it in settings', () =>
        chrome.runtime.openOptionsPage(),
      );
    } else {
      renderState(errorMessage(err), 'Retry', () => void init());
    }
  }
}

/** Channel metadata from the last me() response — used for text-only sinks. */
let channelById: Record<string, ChannelDto> = {};

function setupChannels(channels: ChannelDto[], preferId: string): void {
  channelById = Object.fromEntries(channels.map((c) => [c.id, c]));
  channelSelect.replaceChildren();
  for (const channel of channels) {
    const option = document.createElement('option');
    option.value = channel.id;
    option.textContent =
      channel.type === 'shared'
        ? `${channel.name} · shared`
        : channel.type === 'external'
          ? `${channel.name} · mind`
          : channel.name;
    channelSelect.appendChild(option);
  }
  channelId = channels.some((c) => c.id === preferId) ? preferId : channels[0]?.id ?? '';
  channelSelect.value = channelId;
  channelSelect.addEventListener('change', () => {
    channelId = channelSelect.value;
    void setSettings({ defaultChannelId: channelId });
    syncExternalUi();
    void loadHistory();
  });
  syncExternalUi();
}

function syncExternalUi(): void {
  const ch = channelById[channelId];
  const external = ch?.type === 'external';
  attachBtn.hidden = Boolean(external);
  if (external && staged.length) {
    for (const item of staged) if (item.url) URL.revokeObjectURL(item.url);
    staged = [];
    renderAttachments();
    refreshSendEnabled();
  }
  textInput.placeholder = external
    ? 'Note, quote, or URL → Steward Mind'
    : 'Paste or type…';
}

// ---- compose + send ----

function refreshSendEnabled(): void {
  sendBtn.disabled = textInput.value.trim() === '' && staged.length === 0;
}

textInput.addEventListener('input', refreshSendEnabled);
textInput.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    void send();
  }
});

attachBtn.addEventListener('click', () => fileInput.click());
fileInput.addEventListener('change', () => {
  if (fileInput.files) {
    for (const file of Array.from(fileInput.files)) {
      stageFile(file);
    }
    fileInput.value = '';
  }
});

// Paste an image (screenshot) directly into the composer.
textInput.addEventListener('paste', (e) => {
  const items = e.clipboardData?.items;
  if (!items) return;
  for (const item of Array.from(items)) {
    if (item.kind === 'file') {
      const file = item.getAsFile();
      if (file) {
        e.preventDefault();
        stageFile(file);
      }
    }
  }
});

function stageFile(file: File): void {
  if (channelById[channelId]?.type === 'external') {
    showToast('Steward Mind channels are text-only.', 'bad');
    return;
  }
  const limit = (config?.simpleUploadMb ?? 20) * 1024 * 1024;
  if (file.size > limit) {
    showToast(`${file.name} is larger than ${config?.simpleUploadMb ?? 20} MB — send it from the app.`, 'bad');
    return;
  }
  const url = file.type.startsWith('image/') ? URL.createObjectURL(file) : undefined;
  staged.push({ file, url });
  renderAttachments();
  refreshSendEnabled();
}

function removeStaged(index: number): void {
  const [removed] = staged.splice(index, 1);
  if (removed?.url) {
    URL.revokeObjectURL(removed.url);
  }
  renderAttachments();
  refreshSendEnabled();
}

function renderAttachments(): void {
  attachmentsEl.replaceChildren();
  staged.forEach((item, index) => {
    const chip = document.createElement('span');
    chip.className = 'chip';
    if (item.url) {
      const img = document.createElement('img');
      img.className = 'chip-thumb';
      img.src = item.url; // reused; not re-created per render
      chip.appendChild(img);
    }
    const name = document.createElement('span');
    name.className = 'chip-name';
    name.textContent = item.file.name;
    chip.appendChild(name);
    const remove = document.createElement('button');
    remove.className = 'icon-btn';
    remove.textContent = '×';
    remove.title = 'Remove';
    remove.addEventListener('click', () => removeStaged(index));
    chip.appendChild(remove);
    attachmentsEl.appendChild(chip);
  });
}

sendBtn.addEventListener('click', () => void send());

async function send(): Promise<void> {
  // Re-entrancy guard: a held/rapid Enter or Enter-then-click must not double-send.
  if (!client || !channelId || sending) return;
  sending = true;
  const targetChannel = channelId; // pin the channel for the whole send
  sendBtn.disabled = true;
  try {
    const settings = await getSettings();
    const text = textInput.value;
    if (text.trim() !== '') {
      await client.createTextClip(targetChannel, text, settings.device);
      // Cleared right after success so a later file failure + retry won't resend it.
      if (textInput.value === text) {
        textInput.value = '';
      }
    }
    // Drain staged one at a time, removing each as it lands, so a mid-loop
    // failure leaves only the un-sent files for a clean retry.
    while (staged.length > 0) {
      const item = staged[0];
      const uploaded = await client.uploadFile(targetChannel, item.file, item.file.name);
      await client.createFileClip(targetChannel, uploaded.fileId, settings.device);
      if (item.url) {
        URL.revokeObjectURL(item.url);
      }
      staged.shift();
      renderAttachments();
    }
    await loadHistory();
  } catch (err) {
    showToast(errorMessage(err), 'bad');
  } finally {
    sending = false;
    refreshSendEnabled();
  }
}

// ---- history ----

async function loadHistory(): Promise<void> {
  if (!client || !channelId) return;
  streamEl.replaceChildren();
  const loading = document.createElement('div');
  loading.className = 'state mono';
  loading.textContent = 'Loading…';
  streamEl.appendChild(loading);
  try {
    const page = await client.listClips(channelId, { limit: 20 });
    streamEl.replaceChildren();
    if (page.items.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'state';
      empty.textContent = 'Nothing here yet.';
      streamEl.appendChild(empty);
      return;
    }
    for (const clip of page.items) {
      streamEl.appendChild(renderClip(clip));
    }
  } catch (err) {
    streamEl.replaceChildren();
    const errEl = document.createElement('div');
    errEl.className = 'state error';
    errEl.textContent = errorMessage(err);
    streamEl.appendChild(errEl);
  }
}

function renderClip(clip: ClipDto): HTMLElement {
  const row = document.createElement('div');
  row.className = 'clip-row';

  if (clip.type === 'file' && clip.file && clip.file.mime.startsWith('image/') && config?.thumbnailsEnabled) {
    const img = document.createElement('img');
    img.className = 'clip-thumb';
    img.alt = clip.file.name;
    void client
      ?.fetchThumbnailBlob(clip.channelId, clip.file.id)
      .then((blob) => {
        const url = URL.createObjectURL(blob);
        historyUrls.push(url);
        img.src = url;
      })
      .catch(() => {
        /* leave the tinted placeholder */
      });
    row.appendChild(img);
  }

  const body = document.createElement('div');
  body.className = 'clip-body';
  const content = document.createElement('div');
  if (clip.type === 'text') {
    content.className = 'clip-text';
    content.textContent = clip.text ?? '';
  } else {
    content.textContent = clip.file?.name ?? 'file';
  }
  body.appendChild(content);
  const meta = document.createElement('div');
  meta.className = 'mono';
  meta.textContent = `${clip.origin} · ${relativeTime(clip.createdAt)}${
    clip.file ? ` · ${formatBytes(clip.file.size)}` : ''
  }`;
  body.appendChild(meta);
  row.appendChild(body);

  const actions = document.createElement('div');
  actions.className = 'clip-actions';
  if (clip.type === 'text') {
    actions.appendChild(copyButton(clip.text ?? ''));
  } else if (clip.file) {
    actions.appendChild(downloadButton(clip));
  }
  row.appendChild(actions);
  return row;
}

// Inline SVGs (stroke = currentColor) — robust across platforms, unlike unicode
// glyphs which render as empty boxes where the font lacks them.
const ICON_COPY =
  '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>';
const ICON_CHECK =
  '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>';
const ICON_DOWNLOAD =
  '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>';

function copyButton(text: string): HTMLButtonElement {
  const btn = document.createElement('button');
  btn.className = 'icon-btn';
  btn.title = 'Copy';
  btn.setAttribute('aria-label', 'Copy');
  btn.innerHTML = ICON_COPY;
  btn.addEventListener('click', () => {
    void navigator.clipboard.writeText(text).then(() => {
      btn.classList.add('copied');
      btn.innerHTML = ICON_CHECK;
      setTimeout(() => {
        btn.classList.remove('copied');
        btn.innerHTML = ICON_COPY;
      }, 1200);
    });
  });
  return btn;
}

function downloadButton(clip: ClipDto): HTMLButtonElement {
  const btn = document.createElement('button');
  btn.className = 'icon-btn';
  btn.title = 'Download';
  btn.setAttribute('aria-label', 'Download');
  btn.innerHTML = ICON_DOWNLOAD;
  btn.addEventListener('click', () => {
    if (!client || !clip.file) return;
    btn.disabled = true;
    void client
      .fetchFileBlob(clip.channelId, clip.file.id)
      .then((blob) => {
        const url = URL.createObjectURL(blob);
        // Revoke only once the download has actually consumed the blob — NOT on
        // popup unload, which fires the instant the popup loses focus and would
        // yank the URL out from under an in-flight download.
        chrome.downloads.download({ url, filename: clip.file?.name ?? 'file' }, (downloadId) => {
          if (downloadId === undefined) {
            URL.revokeObjectURL(url);
            return;
          }
          revokeWhenComplete(downloadId, url);
        });
      })
      .catch((err: unknown) => showToast(errorMessage(err), 'bad'))
      .finally(() => {
        btn.disabled = false;
      });
  });
  return btn;
}

/** Revokes a download's blob URL when it finishes (or is interrupted). */
function revokeWhenComplete(downloadId: number, url: string): void {
  const onChanged = (delta: chrome.downloads.DownloadDelta): void => {
    if (delta.id !== downloadId || !delta.state) {
      return;
    }
    if (delta.state.current === 'complete' || delta.state.current === 'interrupted') {
      URL.revokeObjectURL(url);
      chrome.downloads.onChanged.removeListener(onChanged);
    }
  };
  chrome.downloads.onChanged.addListener(onChanged);
}

// ---- helpers ----

function renderState(message: string, actionLabel: string, onClick: () => void): void {
  streamEl.replaceChildren();
  const wrap = document.createElement('div');
  wrap.className = 'state';
  const p = document.createElement('p');
  p.textContent = message;
  const btn = document.createElement('button');
  btn.className = 'btn primary';
  btn.textContent = actionLabel;
  btn.addEventListener('click', onClick);
  wrap.append(p, btn);
  streamEl.appendChild(wrap);
}

function showToast(message: string, kind: 'ok' | 'bad'): void {
  toastEl.textContent = message;
  toastEl.className = `toast ${kind}`;
  toastEl.hidden = false;
  setTimeout(() => {
    toastEl.hidden = true;
  }, 4000);
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function relativeTime(iso: string): string {
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return '';
  const secs = Math.max(0, Math.floor((Date.now() - then) / 1000));
  if (secs < 60) return 'just now';
  const mins = Math.floor(secs / 60);
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return `${Math.floor(hrs / 24)}d ago`;
}

window.addEventListener('unload', () => {
  for (const url of historyUrls) {
    URL.revokeObjectURL(url);
  }
  for (const item of staged) {
    if (item.url) {
      URL.revokeObjectURL(item.url);
    }
  }
});

void init();
