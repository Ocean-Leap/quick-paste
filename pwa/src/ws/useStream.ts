import { useEffect, useRef } from 'react';
import type { HelloMessage, PongMessage, ServerEvent } from '../api/types';

export type ConnectionStatus = 'connecting' | 'open' | 'reconnecting';

/** Server events forwarded to the app (ping is answered inside the hook). */
export type StreamEvent = Exclude<ServerEvent, { event: 'ping' }>;

export interface StreamOptions {
  /**
   * Runs on every (re)connect, before the hello frame is sent: resync local
   * state against the server and return the device label + per-channel ULID
   * cursors for hello. A rejection closes the socket so backoff retries.
   */
  onConnect: () => Promise<Omit<HelloMessage, 'type'>>;
  onEvent: (event: StreamEvent) => void;
  onStatus: (status: ConnectionStatus) => void;
}

const BACKOFF_MS = [1000, 2000, 5000, 15000];

/**
 * Maintains the single app WebSocket to /api/stream (cookie auth). On every
 * (re)connect it awaits options.onConnect() (REST resync) and only then sends
 * hello with the fresh per-channel cursors; it answers pings and reconnects
 * with exponential backoff (1s → 2s → 5s → 15s cap).
 */
export function useStream(options: StreamOptions): void {
  const optionsRef = useRef(options);
  optionsRef.current = options;

  useEffect(() => {
    let socket: WebSocket | null = null;
    let timer: number | null = null;
    let attempt = 0;
    let disposed = false;

    const connect = (): void => {
      if (disposed) return;
      const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
      const ws = new WebSocket(`${scheme}://${location.host}/api/stream`);
      socket = ws;

      ws.onopen = () => {
        optionsRef.current.onStatus('open');
        void (async () => {
          let hello: HelloMessage;
          try {
            hello = { type: 'hello', ...(await optionsRef.current.onConnect()) };
          } catch {
            // Resync failed (e.g. expired session, /api/auth/me 500) — drop the
            // socket so the normal backoff path retries the whole connect +
            // resync. attempt is left untouched so onclose keeps escalating the
            // delay (1s → 2s → 5s → 15s) instead of pinning to the 1s floor.
            ws.close();
            return;
          }
          // The socket may have closed (or the hook unmounted) while awaiting.
          if (disposed || ws.readyState !== WebSocket.OPEN) return;
          // Only now — a fully established, resynced connection — is the
          // backoff counter safe to reset.
          attempt = 0;
          ws.send(JSON.stringify(hello));
        })();
      };

      ws.onmessage = (message: MessageEvent) => {
        let parsed: unknown;
        try {
          parsed = JSON.parse(String(message.data));
        } catch {
          return;
        }
        if (typeof parsed !== 'object' || parsed === null || !('event' in parsed)) return;
        const event = parsed as ServerEvent;
        if (event.event === 'ping') {
          const pong: PongMessage = { type: 'pong' };
          ws.send(JSON.stringify(pong));
          return;
        }
        optionsRef.current.onEvent(event);
      };

      ws.onerror = () => {
        ws.close();
      };

      ws.onclose = () => {
        if (disposed) return;
        optionsRef.current.onStatus('reconnecting');
        const delay = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
        attempt += 1;
        timer = window.setTimeout(connect, delay);
      };
    };

    optionsRef.current.onStatus('connecting');
    connect();

    return () => {
      disposed = true;
      if (timer !== null) window.clearTimeout(timer);
      socket?.close();
    };
  }, []);
}
