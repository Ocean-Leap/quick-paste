const STORAGE_KEY = 'qp.device';

function guessDevice(): string {
  const ua = navigator.userAgent;
  if (/iPad/i.test(ua)) return 'ipad';
  if (/iPhone/i.test(ua)) return 'iphone';
  if (/Android/i.test(ua)) return 'android';
  if (/Macintosh/i.test(ua)) return 'mac';
  if (/Windows/i.test(ua)) return 'windows';
  if (/Linux/i.test(ua)) return 'linux';
  return 'web';
}

export function getDeviceLabel(): string {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored && stored.trim()) return stored.trim();
  } catch {
    // localStorage unavailable — fall through to the guess.
  }
  return guessDevice();
}

export function setDeviceLabel(label: string): void {
  const trimmed = label.trim();
  if (!trimmed) return;
  try {
    localStorage.setItem(STORAGE_KEY, trimmed);
  } catch {
    // Best effort only.
  }
}
