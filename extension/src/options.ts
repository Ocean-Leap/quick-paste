// Settings page: server URL + token, test connection, default channel, device.

import { QuickPasteClient, errorMessage } from './lib/api';
import { getSettings, normalizeServerUrl, serverUrlError, setSettings } from './lib/storage';
import type { ChannelDto } from './lib/types';

const $ = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;

const serverUrlInput = $<HTMLInputElement>('serverUrl');
const tokenInput = $<HTMLInputElement>('token');
const deviceInput = $<HTMLInputElement>('device');
const channelField = $<HTMLDivElement>('channelField');
const channelSelect = $<HTMLSelectElement>('defaultChannel');
const testBtn = $<HTMLButtonElement>('test');
const saveBtn = $<HTMLButtonElement>('save');
const statusEl = $<HTMLDivElement>('status');
const savedEl = $<HTMLDivElement>('saved');

let channels: ChannelDto[] = [];

async function init(): Promise<void> {
  const settings = await getSettings();
  serverUrlInput.value = settings.serverUrl;
  tokenInput.value = settings.token;
  deviceInput.value = settings.device;
  if (settings.serverUrl && settings.token) {
    await loadChannels(settings.defaultChannelId);
  }
}

async function loadChannels(preferId: string): Promise<boolean> {
  const url = normalizeServerUrl(serverUrlInput.value);
  const token = tokenInput.value.trim();
  const urlError = serverUrlError(serverUrlInput.value);
  if (urlError) {
    setStatus(urlError, 'bad');
    return false;
  }
  if (!token) {
    setStatus('Enter your API token.', 'bad');
    return false;
  }
  await requestHostPermission(url);
  try {
    const client = new QuickPasteClient(url, token);
    const me = await client.me();
    channels = me.channels;
    renderChannels(preferId);
    channelField.hidden = false;
    setStatus(`Connected as ${me.user.email} · ${channels.length} channel(s).`, 'ok');
    return true;
  } catch (err) {
    channelField.hidden = true;
    setStatus(errorMessage(err), 'bad');
    return false;
  }
}

function renderChannels(preferId: string): void {
  channelSelect.replaceChildren();
  for (const channel of channels) {
    const option = document.createElement('option');
    option.value = channel.id;
    option.textContent = channel.type === 'shared' ? `${channel.name} (shared)` : channel.name;
    channelSelect.appendChild(option);
  }
  const chosen = channels.some((c) => c.id === preferId) ? preferId : channels[0]?.id ?? '';
  channelSelect.value = chosen;
}

/** Ask for host access to the entered origin so the extension can call it. */
async function requestHostPermission(serverUrl: string): Promise<void> {
  try {
    const origin = new URL(serverUrl).origin + '/*';
    const granted = await chrome.permissions.contains({ origins: [origin] });
    if (!granted) {
      await chrome.permissions.request({ origins: [origin] });
    }
  } catch {
    // Invalid URL is reported by the subsequent fetch; nothing to grant here.
  }
}

function setStatus(message: string, kind: 'ok' | 'bad'): void {
  statusEl.textContent = message;
  statusEl.className = `status-line mono ${kind === 'ok' ? '' : 'error'}`;
}

testBtn.addEventListener('click', () => {
  setStatus('Testing…', 'ok');
  void loadChannels(channelSelect.value);
});

saveBtn.addEventListener('click', () => {
  void save();
});

async function save(): Promise<void> {
  const url = normalizeServerUrl(serverUrlInput.value);
  const token = tokenInput.value.trim();
  const device = deviceInput.value.trim() || 'chrome';
  const urlError = serverUrlError(serverUrlInput.value);
  if (urlError || !token) {
    savedEl.textContent = urlError ?? 'API token is required.';
    savedEl.className = 'status-line mono error';
    return;
  }
  // Ensure we have a valid channel selection; reload if the list is empty.
  if (!channels.length && !(await loadChannels(''))) {
    return;
  }
  await setSettings({
    serverUrl: url,
    token,
    device,
    defaultChannelId: channelSelect.value || channels[0]?.id || '',
  });
  savedEl.textContent = 'Saved.';
  savedEl.className = 'status-line mono';
}

void init();
