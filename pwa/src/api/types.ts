// Mirrors the frozen quick-paste API contract (SPEC §8.2 / §8.5).
// All JSON is camelCase; timestamps are ISO-8601 strings; ids are ULIDs.

export interface UserDto {
  id: string;
  email: string;
  displayName: string;
  isAdmin: boolean;
}

export interface ChannelDto {
  id: string;
  name: string;
  type: 'private' | 'shared';
  createdBy: string;
  role: 'owner' | 'member';
}

export interface MemberDto {
  id: string;
  userId: string | null;
  email: string;
  displayName: string;
  role: 'owner' | 'member';
  status: 'pending' | 'active';
}

export interface FileRefDto {
  id: string;
  name: string;
  size: number;
  mime: string;
}

export interface ClipDto {
  id: string;
  channelId: string;
  type: 'text' | 'file';
  text: string | null;
  file: FileRefDto | null;
  origin: string;
  originUserId: string;
  createdAt: string;
  expiresAt: string;
}

export interface ClipListResponse {
  items: ClipDto[];
  nextCursor: string | null;
}

export interface FileUploadedDto {
  fileId: string;
  name: string;
  size: number;
  mime: string;
}

export interface FileMetaDto {
  id: string;
  name: string;
  size: number;
  mime: string;
  checksum: string;
  createdAt: string;
  expiresAt: string;
}

export interface TokenDto {
  id: string;
  name: string;
  prefix: string;
  scope: string;
  createdAt: string;
  lastUsedAt: string | null;
  expiresAt: string | null;
  revokedAt: string | null;
}

export interface TokenCreatedDto {
  id: string;
  token: string;
  prefix: string;
}

export interface MeResponse {
  user: UserDto;
  channels: ChannelDto[];
}

export interface AuthModeDto {
  mode: 'google' | 'local';
}

/** Client-facing runtime configuration (GET /api/config). */
export interface ClientConfigDto {
  maxTextKb: number;
  simpleUploadMb: number;
  maxFileMb: number;
  userQuotaMb: number;
  thumbnailsEnabled: boolean;
  pushEnabled: boolean;
  vapidPublicKey: string | null;
}

export interface PushSubscriptionCreateRequest {
  endpoint: string;
  keys: { p256dh: string; auth: string };
  device: string;
}

export interface PushSubscriptionCreatedDto {
  id: string;
}

export interface ExportJobDto {
  jobId: string;
  status: 'pending' | 'running' | 'done' | 'failed';
  expiresAt: string | null;
}

/** Error envelope returned by every non-2xx response (SPEC §8.3). */
export interface ApiErrorEnvelope {
  error: {
    code: string;
    message: string;
    details: Record<string, unknown>;
  };
}

export interface CreateClipRequest {
  type: 'text' | 'file';
  text?: string;
  fileId?: string;
  origin: string;
  idempotencyKey?: string;
}

// ---- WebSocket protocol (/api/stream, SPEC §8.5) ----

export interface HelloMessage {
  type: 'hello';
  device: string;
  cursors: Record<string, string | null>;
}

export interface PongMessage {
  type: 'pong';
}

export type ServerEvent =
  | { event: 'clip.created'; channelId: string; clip: ClipDto }
  | { event: 'clip.deleted'; channelId: string; id: string }
  | { event: 'clips.cleared'; channelId: string }
  | { event: 'channel.joined'; channel: ChannelDto }
  | { event: 'channel.left'; channelId: string }
  | { event: 'channel.deleted'; channelId: string }
  | { event: 'catchup.done'; channelId: string; cursor: string | null }
  | { event: 'ping' };
