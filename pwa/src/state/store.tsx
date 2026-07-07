import { createContext, useContext, useMemo, useReducer } from 'react';
import type { Dispatch, ReactNode } from 'react';
import type { ChannelDto, ClientConfigDto, ClipDto, MeResponse, UserDto } from '../api/types';
import type { ConnectionStatus } from '../ws/useStream';

export interface ChannelClipsState {
  /** Newest-first (descending ULID order). */
  clips: ClipDto[];
  /** Cursor for paging older items (beforeId), null when fully loaded. */
  nextCursor: string | null;
  status: 'idle' | 'loading' | 'ready' | 'error';
  error: string | null;
  loadingOlder: boolean;
}

export interface AppState {
  user: UserDto;
  /** Server-provided runtime limits & feature flags — static for the session. */
  config: ClientConfigDto;
  channels: ChannelDto[];
  selectedId: string;
  perChannel: Record<string, ChannelClipsState>;
  /** Max clip ULID seen per channel — the WS catch-up cursor. */
  cursors: Record<string, string | null>;
  /**
   * Channels whose WS catch-up replay has completed on the current socket.
   * Until a channel is synced, live clip.created events render but must not
   * advance its cursor (they can arrive ahead of older replayed items).
   */
  synced: ReadonlySet<string>;
  connection: ConnectionStatus;
}

export type AppAction =
  | { type: 'select-channel'; channelId: string }
  | { type: 'channel-added'; channel: ChannelDto }
  | { type: 'channel-removed'; channelId: string }
  | { type: 'channels-resynced'; channels: ChannelDto[] }
  | { type: 'channel-resynced'; channelId: string; items: ClipDto[]; nextCursor: string | null }
  | { type: 'catchup-done'; channelId: string; cursor: string | null }
  | { type: 'history-loading'; channelId: string }
  | { type: 'history-loaded'; channelId: string; items: ClipDto[]; nextCursor: string | null }
  | { type: 'history-error'; channelId: string; message: string }
  | { type: 'older-loading'; channelId: string }
  | { type: 'clip-upserted'; channelId: string; clip: ClipDto }
  | { type: 'clip-deleted'; channelId: string; id: string }
  | { type: 'channel-cleared'; channelId: string }
  | { type: 'connection'; status: ConnectionStatus };

const EMPTY_CHANNEL: ChannelClipsState = {
  clips: [],
  nextCursor: null,
  status: 'idle',
  error: null,
  loadingOlder: false,
};

function sortChannels(channels: ChannelDto[]): ChannelDto[] {
  return [...channels].sort((a, b) => {
    if (a.type !== b.type) return a.type === 'private' ? -1 : 1;
    return a.name.localeCompare(b.name);
  });
}

export function pickDefaultChannel(channels: ChannelDto[]): string {
  const privateChannel = channels.find((c) => c.type === 'private');
  return privateChannel?.id ?? channels[0]?.id ?? '';
}

/** Inserts or replaces a clip, keeping descending ULID order (dedup by id). */
function upsertClip(clips: ClipDto[], clip: ClipDto): ClipDto[] {
  const existing = clips.findIndex((c) => c.id === clip.id);
  if (existing >= 0) {
    const next = clips.slice();
    next[existing] = clip;
    return next;
  }
  const position = clips.findIndex((c) => c.id < clip.id);
  if (position === -1) return [...clips, clip];
  return [...clips.slice(0, position), clip, ...clips.slice(position)];
}

function advanceCursor(current: string | null | undefined, clipId: string): string {
  return current && current > clipId ? current : clipId;
}

function channelState(state: AppState, channelId: string): ChannelClipsState {
  return state.perChannel[channelId] ?? EMPTY_CHANNEL;
}

function reducer(state: AppState, action: AppAction): AppState {
  switch (action.type) {
    case 'select-channel': {
      if (!state.channels.some((c) => c.id === action.channelId)) return state;
      return { ...state, selectedId: action.channelId };
    }

    case 'channel-added': {
      if (state.channels.some((c) => c.id === action.channel.id)) return state;
      // A channel added live (join event / just created) has no catch-up
      // replay in flight on this socket, so it is synced from the start.
      const synced = new Set(state.synced);
      synced.add(action.channel.id);
      return { ...state, channels: sortChannels([...state.channels, action.channel]), synced };
    }

    case 'channel-removed': {
      const channels = state.channels.filter((c) => c.id !== action.channelId);
      if (channels.length === state.channels.length) return state;
      const perChannel = { ...state.perChannel };
      delete perChannel[action.channelId];
      const cursors = { ...state.cursors };
      delete cursors[action.channelId];
      const synced = new Set(state.synced);
      synced.delete(action.channelId);
      const selectedId =
        state.selectedId === action.channelId ? pickDefaultChannel(channels) : state.selectedId;
      return { ...state, channels, perChannel, cursors, synced, selectedId };
    }

    case 'channels-resynced': {
      const channels = sortChannels(action.channels);
      const ids = new Set(channels.map((c) => c.id));
      const perChannel: Record<string, ChannelClipsState> = {};
      for (const [id, ch] of Object.entries(state.perChannel)) if (ids.has(id)) perChannel[id] = ch;
      const cursors: Record<string, string | null> = {};
      for (const [id, cursor] of Object.entries(state.cursors)) if (ids.has(id)) cursors[id] = cursor;
      const synced = new Set([...state.synced].filter((id) => ids.has(id)));
      const selectedId = ids.has(state.selectedId) ? state.selectedId : pickDefaultChannel(channels);
      return { ...state, channels, perChannel, cursors, synced, selectedId };
    }

    case 'channel-resynced': {
      if (!state.channels.some((c) => c.id === action.channelId)) return state;
      // Reconnect resync: the newest server page REPLACES the local window so
      // items deleted or cleared while offline disappear too.
      return {
        ...state,
        perChannel: {
          ...state.perChannel,
          [action.channelId]: {
            clips: action.items,
            nextCursor: action.nextCursor,
            status: 'ready',
            error: null,
            loadingOlder: false,
          },
        },
        cursors: { ...state.cursors, [action.channelId]: action.items[0]?.id ?? null },
      };
    }

    case 'catchup-done': {
      if (!state.channels.some((c) => c.id === action.channelId)) return state;
      const ch = channelState(state, action.channelId);
      const cursor = [action.cursor ?? '', state.cursors[action.channelId] ?? '', ch.clips[0]?.id ?? '']
        .reduce((a, b) => (a > b ? a : b));
      const synced = new Set(state.synced);
      synced.add(action.channelId);
      return {
        ...state,
        synced,
        cursors: { ...state.cursors, [action.channelId]: cursor || null },
      };
    }

    case 'history-loading': {
      const ch = channelState(state, action.channelId);
      return {
        ...state,
        perChannel: {
          ...state.perChannel,
          [action.channelId]: { ...ch, status: 'loading', error: null },
        },
      };
    }

    case 'history-loaded': {
      if (!state.channels.some((c) => c.id === action.channelId)) return state;
      const ch = channelState(state, action.channelId);
      // Merge (dedup by id) so WS events that raced the fetch are kept.
      const clips = action.items.reduce(upsertClip, ch.clips);
      const cursor = clips.length
        ? advanceCursor(state.cursors[action.channelId], clips[0].id)
        : (state.cursors[action.channelId] ?? null);
      return {
        ...state,
        perChannel: {
          ...state.perChannel,
          [action.channelId]: {
            clips,
            nextCursor: action.nextCursor,
            status: 'ready',
            error: null,
            loadingOlder: false,
          },
        },
        cursors: { ...state.cursors, [action.channelId]: cursor },
      };
    }

    case 'history-error': {
      const ch = channelState(state, action.channelId);
      return {
        ...state,
        perChannel: {
          ...state.perChannel,
          [action.channelId]: {
            ...ch,
            status: ch.status === 'loading' ? 'error' : ch.status,
            error: action.message,
            loadingOlder: false,
          },
        },
      };
    }

    case 'older-loading': {
      const ch = channelState(state, action.channelId);
      return {
        ...state,
        perChannel: {
          ...state.perChannel,
          [action.channelId]: { ...ch, loadingOlder: true, error: null },
        },
      };
    }

    case 'clip-upserted': {
      if (!state.channels.some((c) => c.id === action.channelId)) return state;
      const ch = channelState(state, action.channelId);
      return {
        ...state,
        perChannel: {
          ...state.perChannel,
          [action.channelId]: { ...ch, clips: upsertClip(ch.clips, action.clip) },
        },
        // Until this channel's catch-up replay finishes, live events must not
        // advance the cursor — older unreplayed clips could otherwise be
        // skipped for good if the socket drops mid-replay.
        cursors: state.synced.has(action.channelId)
          ? {
              ...state.cursors,
              [action.channelId]: advanceCursor(state.cursors[action.channelId], action.clip.id),
            }
          : state.cursors,
      };
    }

    case 'clip-deleted': {
      const ch = channelState(state, action.channelId);
      const clips = ch.clips.filter((c) => c.id !== action.id);
      if (clips.length === ch.clips.length) return state;
      return {
        ...state,
        perChannel: { ...state.perChannel, [action.channelId]: { ...ch, clips } },
      };
    }

    case 'channel-cleared': {
      const ch = channelState(state, action.channelId);
      return {
        ...state,
        perChannel: {
          ...state.perChannel,
          [action.channelId]: { ...ch, clips: [], nextCursor: null },
        },
      };
    }

    case 'connection': {
      if (state.connection === action.status) return state;
      // Any transition means a socket change: every channel must re-earn its
      // synced flag via catchup.done on the (next) connection.
      return { ...state, connection: action.status, synced: new Set<string>() };
    }
  }
}

function initialState({ me, config }: { me: MeResponse; config: ClientConfigDto }): AppState {
  const channels = sortChannels(me.channels);
  return {
    user: me.user,
    config,
    channels,
    selectedId: pickDefaultChannel(channels),
    perChannel: {},
    cursors: {},
    synced: new Set<string>(),
    connection: 'connecting',
  };
}

interface StoreValue {
  state: AppState;
  dispatch: Dispatch<AppAction>;
}

const StoreContext = createContext<StoreValue | null>(null);

export function AppStoreProvider({
  me,
  config,
  children,
}: {
  me: MeResponse;
  config: ClientConfigDto;
  children: ReactNode;
}) {
  const [state, dispatch] = useReducer(reducer, { me, config }, initialState);
  const value = useMemo(() => ({ state, dispatch }), [state]);
  return <StoreContext.Provider value={value}>{children}</StoreContext.Provider>;
}

export function useAppStore(): StoreValue {
  const value = useContext(StoreContext);
  if (!value) throw new Error('useAppStore must be used inside <AppStoreProvider>');
  return value;
}
