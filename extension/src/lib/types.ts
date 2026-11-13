// Mirrors the quick-paste API contract (see pwa/src/api/types.ts). Kept minimal —
// only the shapes the extension actually consumes.

export interface UserDto {
  id: string;
  email: string;
  displayName: string;
  isAdmin: boolean;
}

export interface ChannelDto {
  id: string;
  name: string;
  type: 'private' | 'shared' | 'external';
  createdBy: string;
  role: 'owner' | 'member';
  sink?: {
    provider: string;
    baseUrl: string;
    tokenPrefix: string;
    defaultTags: string[] | null;
  } | null;
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
  delivery?: {
    status: string;
    kind: string | null;
    externalItemId: string | null;
    attempts: number;
    error: string | null;
  } | null;
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

export interface MeResponse {
  user: UserDto;
  channels: ChannelDto[];
}

export interface ClientConfigDto {
  maxTextKb: number;
  simpleUploadMb: number;
  maxFileMb: number;
  userQuotaMb: number;
  thumbnailsEnabled: boolean;
  pushEnabled: boolean;
  vapidPublicKey: string | null;
}

export interface ApiErrorEnvelope {
  error: { code: string; message: string; details: Record<string, unknown> };
}
