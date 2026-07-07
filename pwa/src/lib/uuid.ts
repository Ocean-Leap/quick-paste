/**
 * UUID v4 that also works outside secure contexts: crypto.randomUUID is only
 * defined on HTTPS/localhost origins, but the PWA may be opened over plain
 * HTTP on a LAN (e.g. http://<host-ip>:8080). crypto.getRandomValues is
 * available everywhere, so fall back to assembling the UUID from it.
 */
export function randomId(): string {
  const c = globalThis.crypto;
  if (typeof c.randomUUID === 'function') {
    return c.randomUUID();
  }
  const bytes = new Uint8Array(16);
  c.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40; // version 4
  bytes[8] = (bytes[8] & 0x3f) | 0x80; // RFC 4122 variant
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0'));
  return (
    hex.slice(0, 4).join('') +
    '-' +
    hex.slice(4, 6).join('') +
    '-' +
    hex.slice(6, 8).join('') +
    '-' +
    hex.slice(8, 10).join('') +
    '-' +
    hex.slice(10).join('')
  );
}
