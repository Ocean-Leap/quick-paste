import { useEffect, useRef, useState } from 'react';
import { clearClips, deleteClip, errorMessage, fileDownloadUrl } from '../api/client';
import type { ChannelDto, ClipDto } from '../api/types';
import { copyText } from '../lib/clipboard';
import { formatBytes } from '../lib/format';
import { expiresLabel, lifeFraction, relativeTime, useNow } from '../lib/relativeTime';
import { loadOlder } from '../state/actions';
import { useAppStore } from '../state/store';
import { FileThumbnail } from './FileThumbnail';
import { CheckIcon, CopyIcon, DownloadIcon, FileIcon, TextIcon, TrashIcon } from './icons';
import { Lifeline } from './Lifeline';

/** A clip first seen less than this old animates in as a live arrival. */
const ARRIVAL_WINDOW_MS = 5_000;

/**
 * The single stream: every clip in the channel, newest first. The first row
 * carries the "latest" emphasis — mono eyebrow, larger content, solid
 * copy/download — where the separate hero card used to live.
 */
export function History({ channel }: { channel: ChannelDto }) {
  const { state, dispatch } = useAppStore();
  const now = useNow();
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [expiryShownId, setExpiryShownId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const sentinelRef = useRef<HTMLDivElement | null>(null);
  const loadMoreRef = useRef<() => void>(() => undefined);

  const ch = state.perChannel[channel.id];
  const clips = ch?.clips ?? [];

  // Live arrivals (WS inserts / own sends) get the entrance animation; clips
  // loaded via history pagination — or re-shown after switching channels away
  // and back — do not. A clip counts as "arrived" only when it is newly present
  // AND still fresher than the arrival window. This runs in an effect (never in
  // the render body, which would be unsafe to mutate under StrictMode) keyed on
  // the current clip-id list; the seen/arrived sets are pruned to the current
  // ids each run so they stay bounded and an old clip re-appearing can't
  // re-animate.
  const seenIdsRef = useRef(new Set<string>());
  const [arrivedIds, setArrivedIds] = useState<Set<string>>(() => new Set());
  const clipIdsKey = clips.map((c) => c.id).join(',');
  useEffect(() => {
    const seen = seenIdsRef.current;
    const currentIds = new Set(clips.map((c) => c.id));
    setArrivedIds((prev) => {
      const next = new Set<string>();
      // Keep prior arrivals still on screen so their glow isn't cut short.
      for (const id of prev) if (currentIds.has(id)) next.add(id);
      for (const clip of clips) {
        if (seen.has(clip.id)) continue;
        if (Date.now() - Date.parse(clip.createdAt) < ARRIVAL_WINDOW_MS) next.add(clip.id);
      }
      return next;
    });
    seenIdsRef.current = currentIds;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clipIdsKey]);

  loadMoreRef.current = () => {
    if (ch && ch.status === 'ready' && ch.nextCursor && !ch.loadingOlder) {
      void loadOlder(dispatch, channel.id, ch.nextCursor);
    }
  };

  useEffect(() => {
    const sentinel = sentinelRef.current;
    if (!sentinel) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) loadMoreRef.current();
      },
      { rootMargin: '200px' },
    );
    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [channel.id]);

  // "Copied" is a moment, not a state — revert after 1.5s.
  useEffect(() => {
    if (!copiedId) return;
    const timer = window.setTimeout(() => setCopiedId(null), 1500);
    return () => window.clearTimeout(timer);
  }, [copiedId]);

  const handleCopy = async (clip: ClipDto) => {
    const ok = await copyText(clip.text ?? '');
    setCopiedId(ok ? clip.id : null);
    setError(ok ? null : 'Copy failed — open the item and copy it manually.');
  };

  const handleDelete = async (clip: ClipDto) => {
    setError(null);
    try {
      await deleteClip(channel.id, clip.id);
      dispatch({ type: 'clip-deleted', channelId: channel.id, id: clip.id });
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  const handleClear = async () => {
    if (!window.confirm(`Clear all items in "${channel.name}"? This cannot be undone.`)) return;
    setError(null);
    try {
      await clearClips(channel.id);
      dispatch({ type: 'channel-cleared', channelId: channel.id });
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  const rowClass = (clip: ClipDto, latest: boolean): string => {
    const classes = ['clip-row'];
    if (latest) classes.push('latest');
    if (lifeFraction(clip.createdAt, clip.expiresAt, now) <= 0.2) classes.push('fading');
    if (arrivedIds.has(clip.id)) classes.push('arrived');
    return classes.join(' ');
  };

  const timeSwap = (clip: ClipDto) => (
    <button
      type="button"
      className={expiryShownId === clip.id ? 'time-swap show-exp' : 'time-swap'}
      aria-label={expiresLabel(clip.expiresAt, now)}
      onClick={() => setExpiryShownId((prev) => (prev === clip.id ? null : clip.id))}
    >
      <span className="t-rel">{relativeTime(clip.createdAt, now)}</span>
      <span className="t-exp">{expiresLabel(clip.expiresAt, now)}</span>
    </button>
  );

  const deleteButton = (clip: ClipDto) => (
    <button
      type="button"
      className="icon-btn"
      aria-label="Delete item"
      onClick={() => void handleDelete(clip)}
    >
      <TrashIcon size={16} />
    </button>
  );

  const latestRow = (clip: ClipDto) => (
    <div className="clip-main latest-main">
      <div className="latest-eyebrow">
        <span>Latest · {clip.origin} ·</span>
        {timeSwap(clip)}
      </div>
      {clip.type === 'text' ? (
        <pre className="latest-text">{clip.text ?? ''}</pre>
      ) : (
        <div className="latest-file">
          {clip.file?.mime.startsWith('image/') && (
            <div className="latest-thumb-wrap">
              <FileThumbnail
                key={clip.file.id}
                channelId={channel.id}
                file={clip.file}
                className="latest-thumb"
                iconSize={40}
              />
            </div>
          )}
          <span className="latest-file-name">{clip.file?.name ?? 'file'}</span>
          {clip.file && <span className="muted">{formatBytes(clip.file.size)}</span>}
        </div>
      )}
      <div className="latest-actions">
        {clip.type === 'text' ? (
          <button type="button" className="btn primary" onClick={() => void handleCopy(clip)}>
            {copiedId === clip.id ? (
              <>
                Copied <CheckIcon size={14} />
              </>
            ) : (
              'Copy'
            )}
          </button>
        ) : (
          clip.file && (
            <a
              className="btn primary"
              href={fileDownloadUrl(channel.id, clip.file.id)}
              download={clip.file.name}
            >
              Download
            </a>
          )
        )}
        {deleteButton(clip)}
      </div>
    </div>
  );

  const standardRow = (clip: ClipDto) => (
    <div className="clip-main">
      <span className="clip-icon">
        {clip.type === 'text' ? (
          <TextIcon size={16} />
        ) : clip.file ? (
          <FileThumbnail
            key={clip.file.id}
            channelId={channel.id}
            file={clip.file}
            className="clip-thumb"
            iconSize={16}
          />
        ) : (
          <FileIcon size={16} />
        )}
      </span>
      <div className="clip-body">
        {clip.type === 'text' ? (
          <p className="clip-snippet">{clip.text ?? ''}</p>
        ) : (
          <p className="clip-file">
            {clip.file?.name ?? 'file'}{' '}
            {clip.file && <span className="muted">{formatBytes(clip.file.size)}</span>}
          </p>
        )}
        <p className="clip-meta">
          {clip.origin} · {timeSwap(clip)}
        </p>
      </div>
      <div className="clip-actions">
        {clip.type === 'text' ? (
          <button
            type="button"
            className={copiedId === clip.id ? 'icon-btn copied' : 'icon-btn'}
            aria-label="Copy text"
            onClick={() => void handleCopy(clip)}
          >
            {copiedId === clip.id ? <CheckIcon size={16} /> : <CopyIcon size={16} />}
          </button>
        ) : (
          clip.file && (
            <a
              className="icon-btn"
              aria-label={`Download ${clip.file.name}`}
              href={fileDownloadUrl(channel.id, clip.file.id)}
              download={clip.file.name}
            >
              <DownloadIcon size={16} />
            </a>
          )
        )}
        {deleteButton(clip)}
      </div>
    </div>
  );

  return (
    <section className="stream card" aria-label="Stream">
      <header className="stream-toolbar">
        <span className="stream-eyebrow">Stream</span>
        {clips.length > 0 && (
          <button type="button" className="btn danger-ghost small" onClick={() => void handleClear()}>
            Clear channel
          </button>
        )}
      </header>
      {error && <p className="error">{error}</p>}
      {!ch || (ch.status === 'loading' && clips.length === 0) ? (
        <div className="history-skeleton">
          <div className="skeleton skeleton-line" />
          <div className="skeleton skeleton-block" />
          <div className="skeleton skeleton-line short" />
        </div>
      ) : clips.length === 0 ? (
        ch.status === 'error' && ch.error ? (
          <p className="error">{ch.error}</p>
        ) : (
          <div className="empty">
            <h3 className="empty-headline">Nothing here yet</h3>
            <p>Paste or drop something — it appears on every device in about a second.</p>
          </div>
        )
      ) : (
        <ul className="clip-list">
          {clips.map((clip, index) => (
            <li key={clip.id} className={rowClass(clip, index === 0)}>
              {index === 0 ? latestRow(clip) : standardRow(clip)}
              <Lifeline clip={clip} now={now} />
            </li>
          ))}
        </ul>
      )}
      <div ref={sentinelRef} className="history-sentinel" aria-hidden="true" />
      {ch?.loadingOlder && <p className="muted">Loading older items…</p>}
    </section>
  );
}
