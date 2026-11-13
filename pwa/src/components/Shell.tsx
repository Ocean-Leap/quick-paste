import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, getMe } from '../api/client';
import type { ChannelDto, HelloMessage } from '../api/types';
import { getDeviceLabel } from '../lib/device';
import { takeShare } from '../lib/shareStore';
import { loadHistory, resyncChannel } from '../state/actions';
import { pickDefaultChannel, useAppStore } from '../state/store';
import { useStream } from '../ws/useStream';
import type { StreamEvent } from '../ws/useStream';
import { AccountSettings } from './AccountSettings';
import { ChannelDetail } from './ChannelDetail';
import { Composer } from './Composer';
import type { IncomingShare } from './Composer';
import { History } from './History';
import { TopBar } from './TopBar';

export function Shell() {
  const { state, dispatch } = useAppStore();
  const stateRef = useRef(state);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [detailOpen, setDetailOpen] = useState(false);
  const [pendingShare, setPendingShare] = useState<IncomingShare | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [offlineLong, setOfflineLong] = useState(false);

  useEffect(() => {
    stateRef.current = state;
  });

  // Startup deep links: ?channel=X selects a channel (notification clicks on a
  // fresh window). A stashed Web Share Target payload is picked up regardless of
  // the ?share=pending marker: sign-in redirects to '/' and drops the query, so
  // relying on it would orphan the share in IndexedDB. We always drain the store
  // once on mount instead, guarded so a StrictMode double-invoke can't consume
  // it twice or race the SW message path.
  const shareTakenRef = useRef(false);
  useEffect(() => {
    const params = new URLSearchParams(location.search);
    const channelId = params.get('channel');
    if (channelId) dispatch({ type: 'select-channel', channelId });
    if (channelId || params.has('share')) {
      history.replaceState(null, '', location.pathname);
    }
    if (shareTakenRef.current) return;
    shareTakenRef.current = true;
    void takeShare().then((share) => {
      if (!share) return;
      const text =
        [share.text, share.url]
          .map((part) => part.trim())
          .filter(Boolean)
          .join('\n') || share.title.trim();
      if (text || share.files.length) setPendingShare({ text, files: share.files });
    });
  }, [dispatch]);

  // Notification clicks in an already-open app arrive as SW messages.
  useEffect(() => {
    if (!('serviceWorker' in navigator)) return;
    const onMessage = (event: MessageEvent) => {
      const data = event.data as { type?: unknown; channelId?: unknown } | null;
      if (data && data.type === 'open-channel' && typeof data.channelId === 'string') {
        dispatch({ type: 'select-channel', channelId: data.channelId });
      }
    };
    navigator.serviceWorker.addEventListener('message', onMessage);
    return () => navigator.serviceWorker.removeEventListener('message', onMessage);
  }, [dispatch]);

  const selected = state.channels.find((c) => c.id === state.selectedId) ?? null;
  const selectedHasDetail = selected?.type === 'shared' || selected?.type === 'external';

  // Toasts are moments — dismiss after 2s.
  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(null), 2000);
    return () => window.clearTimeout(timer);
  }, [toast]);

  // The status dot covers brief reconnects; the banner only appears once the
  // stream has been down for more than 5 seconds.
  useEffect(() => {
    if (state.connection === 'open') {
      setOfflineLong(false);
      return;
    }
    const timer = window.setTimeout(() => setOfflineLong(true), 5000);
    return () => window.clearTimeout(timer);
  }, [state.connection]);

  // Global paste-to-send: paste anywhere (outside inputs, no modal open) and
  // files upload straight into the selected channel; text lands in the
  // composer for the user to confirm with Enter.
  useEffect(() => {
    if (settingsOpen || !selected) return;
    const onPaste = (event: ClipboardEvent) => {
      const target = event.target;
      if (
        target instanceof HTMLElement &&
        (target.closest('input, textarea, select') || target.isContentEditable)
      ) {
        return;
      }
      const data = event.clipboardData;
      if (!data) return;
      const files = Array.from(data.files);
      if (files.length > 0) {
        event.preventDefault();
        setPendingShare({ text: '', files });
        setToast('pasted from clipboard');
        return;
      }
      const text = data.getData('text/plain');
      if (text) {
        event.preventDefault();
        setPendingShare({ text, files: [] });
      }
    };
    window.addEventListener('paste', onPaste);
    return () => window.removeEventListener('paste', onPaste);
  }, [settingsOpen, selected]);

  // Fetch history the first time a channel is selected.
  useEffect(() => {
    const channelId = state.selectedId;
    if (!channelId) return;
    const ch = state.perChannel[channelId];
    if (!ch || ch.status === 'idle') void loadHistory(dispatch, channelId);
  }, [state.selectedId, state.perChannel, dispatch]);

  // Close the members panel when the selection is no longer a shared channel.
  useEffect(() => {
    if (!selectedHasDetail) setDetailOpen(false);
  }, [selectedHasDetail, state.selectedId]);

  const handleEvent = useCallback(
    (event: StreamEvent) => {
      switch (event.event) {
        case 'clip.created':
          dispatch({ type: 'clip-upserted', channelId: event.channelId, clip: event.clip });
          break;
        case 'clip.deleted':
          dispatch({ type: 'clip-deleted', channelId: event.channelId, id: event.id });
          break;
        case 'clips.cleared':
          dispatch({ type: 'channel-cleared', channelId: event.channelId });
          break;
        case 'channel.joined':
          dispatch({ type: 'channel-added', channel: event.channel });
          // No cursor yet for the new channel — seed it from recent history.
          void loadHistory(dispatch, event.channel.id);
          break;
        case 'channel.left':
        case 'channel.deleted':
          dispatch({ type: 'channel-removed', channelId: event.channelId });
          break;
        case 'catchup.done':
          dispatch({ type: 'catchup-done', channelId: event.channelId, cursor: event.cursor });
          break;
      }
    },
    [dispatch],
  );

  // Runs on every WS (re)connect, before hello: reconcile the channel list
  // (channel.joined/left events were lost while offline) and replace the
  // loaded clip windows with server truth (picks up offline deletes/clears),
  // then hand the fresh cursors to the hello frame. Throws on failure, which
  // makes useStream drop the socket and retry with backoff.
  const handleConnect = useCallback(async (): Promise<Omit<HelloMessage, 'type'>> => {
    let me: Awaited<ReturnType<typeof getMe>>;
    try {
      me = await getMe();
    } catch (err) {
      // A 401 during resync means the session is gone — reloading re-bootstraps
      // the app, which sees the 401 and renders SignIn instead of stranding the
      // user on "reconnecting". Other errors propagate so useStream closes the
      // socket and backs off.
      if (err instanceof ApiError && err.status === 401) {
        location.reload();
      }
      throw err;
    }
    const previous = stateRef.current;
    dispatch({ type: 'channels-resynced', channels: me.channels });
    const survivors = new Set(me.channels.map((c) => c.id));
    const selectedId = survivors.has(previous.selectedId)
      ? previous.selectedId
      : pickDefaultChannel(me.channels);
    const toRefresh = new Set(Object.keys(previous.perChannel).filter((id) => survivors.has(id)));
    if (selectedId) toRefresh.add(selectedId);
    const cursors: Record<string, string | null> = {};
    for (const channel of me.channels) cursors[channel.id] = previous.cursors[channel.id] ?? null;
    await Promise.all(
      [...toRefresh].map(async (channelId) => {
        cursors[channelId] = await resyncChannel(dispatch, channelId);
      }),
    );
    return { device: getDeviceLabel(), cursors };
  }, [dispatch]);

  useStream({
    onConnect: handleConnect,
    onEvent: handleEvent,
    onStatus: (status) => dispatch({ type: 'connection', status }),
  });

  const handleChannelCreated = (channel: ChannelDto) => {
    dispatch({ type: 'channel-added', channel });
    dispatch({ type: 'select-channel', channelId: channel.id });
  };

  return (
    <div className="app">
      <TopBar
        user={state.user}
        channels={state.channels}
        selected={selected}
        connection={state.connection}
        onSelect={(channelId) => dispatch({ type: 'select-channel', channelId })}
        onChannelCreated={handleChannelCreated}
        onToggleChannelDetail={selectedHasDetail ? () => setDetailOpen((v) => !v) : null}
        onOpenSettings={() => setSettingsOpen(true)}
      />
      {offlineLong && (
        <div className="conn-banner" role="status">
          offline — reconnecting…
        </div>
      )}
      {selected ? (
        <main className="layout">
          <Composer
            key={selected.id}
            channel={selected}
            share={pendingShare}
            onShareConsumed={() => setPendingShare(null)}
            onToast={setToast}
          />
          {detailOpen && selectedHasDetail && (
            <ChannelDetail channel={selected} onClose={() => setDetailOpen(false)} />
          )}
          <History channel={selected} />
        </main>
      ) : (
        <main className="layout">
          <p className="muted center">No channels available.</p>
        </main>
      )}
      {toast && (
        <div className="toast" role="status">
          {toast}
        </div>
      )}
      {settingsOpen && <AccountSettings user={state.user} onClose={() => setSettingsOpen(false)} />}
    </div>
  );
}
