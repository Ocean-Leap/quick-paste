// Persisted extension settings (chrome.storage.local). The token is the user's
// quick-paste API token — origin-scoped to their own instance.

export interface Settings {
  serverUrl: string;
  token: string;
  device: string;
  defaultChannelId: string;
}

const DEFAULTS: Settings = {
  serverUrl: '',
  token: '',
  device: 'chrome',
  defaultChannelId: '',
};

export async function getSettings(): Promise<Settings> {
  const stored = await chrome.storage.local.get(DEFAULTS);
  return { ...DEFAULTS, ...stored } as Settings;
}

export async function setSettings(patch: Partial<Settings>): Promise<void> {
  await chrome.storage.local.set(patch);
}

export function isConfigured(settings: Settings): boolean {
  return settings.serverUrl.trim() !== '' && settings.token.trim() !== '';
}

/** Normalizes a user-entered base URL: trims, strips a trailing slash. */
export function normalizeServerUrl(raw: string): string {
  return raw.trim().replace(/\/+$/, '');
}

/**
 * The API token is a full-access credential sent as a Bearer header, so the
 * server URL must be HTTPS — the only exception is loopback for local dev.
 * Prevents the token from ever going out over cleartext http.
 */
export function serverUrlError(raw: string): string | null {
  const url = normalizeServerUrl(raw);
  if (!url) return 'Enter your quick-paste server URL.';
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return 'That is not a valid URL.';
  }
  const host = parsed.hostname;
  const loopback = host === 'localhost' || host === '127.0.0.1' || host === '[::1]' || host === '::1';
  if (parsed.protocol === 'https:' || (parsed.protocol === 'http:' && loopback)) {
    return null;
  }
  return 'Use an https:// URL (http is allowed only for localhost).';
}
