import { useEffect, useState } from 'react';

/** Formats an ISO-8601 timestamp as a short relative time ("4m ago"). */
export function relativeTime(iso: string, now: number = Date.now()): string {
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return '';
  const seconds = Math.floor(Math.max(0, now - then) / 1000);
  if (seconds < 10) return 'just now';
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d ago`;
  return new Date(then).toLocaleDateString();
}

/**
 * Remaining life of a clip as a fraction of its full TTL, clamped to 0..1.
 * 1 = freshly created, 0 = expired.
 */
export function lifeFraction(createdAt: string, expiresAt: string, now: number): number {
  const start = Date.parse(createdAt);
  const end = Date.parse(expiresAt);
  if (Number.isNaN(start) || Number.isNaN(end) || end <= start) return 1;
  return Math.min(1, Math.max(0, (end - now) / (end - start)));
}

/** Human label for the lifeline / hover caption: "expires in 2h 14m". */
export function expiresLabel(expiresAt: string, now: number): string {
  const end = Date.parse(expiresAt);
  if (Number.isNaN(end)) return '';
  const seconds = Math.floor((end - now) / 1000);
  if (seconds <= 0) return 'expired';
  let span: string;
  if (seconds < 60) {
    span = `${seconds}s`;
  } else if (seconds < 3600) {
    span = `${Math.floor(seconds / 60)}m`;
  } else if (seconds < 86_400) {
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    span = minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`;
  } else {
    const days = Math.floor(seconds / 86_400);
    const hours = Math.floor((seconds % 86_400) / 3600);
    span = hours > 0 ? `${days}d ${hours}h` : `${days}d`;
  }
  return `expires in ${span}`;
}

/** Re-renders on an interval so relative timestamps stay fresh. */
export function useNow(intervalMs = 30_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(timer);
  }, [intervalMs]);
  return now;
}
