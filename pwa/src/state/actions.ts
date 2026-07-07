import type { Dispatch } from 'react';
import { errorMessage, listClips } from '../api/client';
import type { AppAction } from './store';

const PAGE_SIZE = 50;

/** Loads (or reloads) the newest page of a channel's history. */
export async function loadHistory(dispatch: Dispatch<AppAction>, channelId: string): Promise<void> {
  dispatch({ type: 'history-loading', channelId });
  try {
    const page = await listClips(channelId, { limit: PAGE_SIZE });
    dispatch({
      type: 'history-loaded',
      channelId,
      items: page.items,
      nextCursor: page.nextCursor,
    });
  } catch (err) {
    dispatch({ type: 'history-error', channelId, message: errorMessage(err) });
  }
}

/**
 * Reconnect resync: refetches the newest page and REPLACES the channel's clip
 * window with it (dropping anything deleted/cleared while offline). Returns
 * the fresh WS cursor — the newest clip id, or null for an empty channel.
 * Throws on failure so the caller can abort the WS hello and retry.
 */
export async function resyncChannel(
  dispatch: Dispatch<AppAction>,
  channelId: string,
): Promise<string | null> {
  const page = await listClips(channelId, { limit: PAGE_SIZE });
  dispatch({
    type: 'channel-resynced',
    channelId,
    items: page.items,
    nextCursor: page.nextCursor,
  });
  return page.items[0]?.id ?? null;
}

/** Pages older history using the channel's nextCursor (beforeId). */
export async function loadOlder(
  dispatch: Dispatch<AppAction>,
  channelId: string,
  beforeId: string,
): Promise<void> {
  dispatch({ type: 'older-loading', channelId });
  try {
    const page = await listClips(channelId, { beforeId, limit: PAGE_SIZE });
    dispatch({
      type: 'history-loaded',
      channelId,
      items: page.items,
      nextCursor: page.nextCursor,
    });
  } catch (err) {
    dispatch({ type: 'history-error', channelId, message: errorMessage(err) });
  }
}
