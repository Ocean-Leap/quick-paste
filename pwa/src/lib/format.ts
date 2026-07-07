/** Formats a byte count as a human-readable size ("2.4 MB"). */
export function formatBytes(size: number): string {
  if (!Number.isFinite(size) || size < 0) return '';
  if (size < 1024) return `${size} B`;
  let value = size;
  let unit = 'B';
  for (const next of ['KB', 'MB', 'GB', 'TB']) {
    value /= 1024;
    unit = next;
    if (value < 1024) break;
  }
  return `${value >= 100 ? Math.round(value) : value.toFixed(1)} ${unit}`;
}
