import type {
  ApiErrorEnvelope,
  AuthModeDto,
  ChannelDto,
  ClientConfigDto,
  ClipDto,
  ClipListResponse,
  CreateClipRequest,
  ExportJobDto,
  MeResponse,
  MemberDto,
  PushSubscriptionCreateRequest,
  PushSubscriptionCreatedDto,
  TokenCreatedDto,
  TokenDto,
} from './types';

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details: Record<string, unknown>;

  constructor(status: number, code: string, message: string, details: Record<string, unknown> = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

export function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : 'Something went wrong.';
}

/** Reads the double-submit CSRF cookie set by the server. */
export function csrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)qp_csrf=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : null;
}

async function parseError(res: Response): Promise<ApiError> {
  let code = 'unknown';
  let message = `Request failed (${res.status})`;
  let details: Record<string, unknown> = {};
  try {
    const body = (await res.json()) as Partial<ApiErrorEnvelope>;
    if (body.error) {
      code = body.error.code;
      message = body.error.message;
      details = body.error.details ?? {};
    }
  } catch {
    // Not a JSON envelope — keep the generic message.
  }
  return new ApiError(res.status, code, message, details);
}

async function send(method: string, path: string, body?: unknown): Promise<Response> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET' && method !== 'HEAD') {
    const token = csrfToken();
    if (token) headers['X-CSRF-Token'] = token;
  }
  const res = await fetch(path, {
    method,
    headers,
    credentials: 'include',
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) throw await parseError(res);
  return res;
}

async function json<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await send(method, path, body);
  return (await res.json()) as T;
}

async function empty(method: string, path: string, body?: unknown): Promise<void> {
  await send(method, path, body);
}

// ---- Auth & account ----

export function getMe(): Promise<MeResponse> {
  return json<MeResponse>('GET', '/api/auth/me');
}

export function getAuthMode(): Promise<AuthModeDto> {
  return json<AuthModeDto>('GET', '/api/auth/mode');
}

export function loginLocal(password: string): Promise<void> {
  return empty('POST', '/api/auth/login-local', { password });
}

export function logout(): Promise<void> {
  return empty('POST', '/api/auth/logout');
}

export function logoutAll(): Promise<void> {
  return empty('POST', '/api/account/logout-all');
}

export function deleteAccount(): Promise<void> {
  return empty('DELETE', '/api/account');
}

export function requestExport(): Promise<ExportJobDto> {
  return json<ExportJobDto>('POST', '/api/account/export');
}

export function getExportJob(jobId: string): Promise<ExportJobDto> {
  return json<ExportJobDto>('GET', `/api/account/export/${encodeURIComponent(jobId)}`);
}

export function exportDownloadUrl(jobId: string): string {
  return `/api/account/export/${encodeURIComponent(jobId)}/download`;
}

// ---- Client config ----

export function getConfig(): Promise<ClientConfigDto> {
  return json<ClientConfigDto>('GET', '/api/config');
}

// ---- Push subscriptions ----

export function createPushSubscription(
  request: PushSubscriptionCreateRequest,
): Promise<PushSubscriptionCreatedDto> {
  return json<PushSubscriptionCreatedDto>('POST', '/api/push/subscriptions', request);
}

export function deletePushSubscription(subscriptionId: string): Promise<void> {
  return empty('DELETE', `/api/push/subscriptions/${encodeURIComponent(subscriptionId)}`);
}

// ---- Channels & membership ----

export function createChannel(name: string): Promise<ChannelDto> {
  return json<ChannelDto>('POST', '/api/channels', { name });
}

export function deleteChannel(channelId: string): Promise<void> {
  return empty('DELETE', `/api/channels/${encodeURIComponent(channelId)}`);
}

export function listMembers(channelId: string): Promise<{ items: MemberDto[] }> {
  return json<{ items: MemberDto[] }>('GET', `/api/channels/${encodeURIComponent(channelId)}/members`);
}

export function inviteMember(channelId: string, email: string): Promise<MemberDto> {
  return json<MemberDto>('POST', `/api/channels/${encodeURIComponent(channelId)}/members`, { email });
}

export function removeMember(channelId: string, memberId: string): Promise<void> {
  return empty(
    'DELETE',
    `/api/channels/${encodeURIComponent(channelId)}/members/${encodeURIComponent(memberId)}`,
  );
}

// ---- Clips ----

export function createClip(channelId: string, request: CreateClipRequest): Promise<ClipDto> {
  return json<ClipDto>('POST', `/api/channels/${encodeURIComponent(channelId)}/clips`, request);
}

export function listClips(
  channelId: string,
  options: { beforeId?: string; limit?: number } = {},
): Promise<ClipListResponse> {
  const params = new URLSearchParams();
  if (options.beforeId) params.set('beforeId', options.beforeId);
  if (options.limit !== undefined) params.set('limit', String(options.limit));
  const query = params.toString();
  return json<ClipListResponse>(
    'GET',
    `/api/channels/${encodeURIComponent(channelId)}/clips${query ? `?${query}` : ''}`,
  );
}

export function deleteClip(channelId: string, clipId: string): Promise<void> {
  return empty(
    'DELETE',
    `/api/channels/${encodeURIComponent(channelId)}/clips/${encodeURIComponent(clipId)}`,
  );
}

export function clearClips(channelId: string): Promise<void> {
  return empty('DELETE', `/api/channels/${encodeURIComponent(channelId)}/clips`);
}

// ---- Files ----

export function fileDownloadUrl(channelId: string, fileId: string): string {
  return `/api/channels/${encodeURIComponent(channelId)}/files/${encodeURIComponent(fileId)}`;
}

/** JPEG thumbnail for image files; the server answers 404 for anything else. */
export function fileThumbnailUrl(channelId: string, fileId: string): string {
  return `/api/channels/${encodeURIComponent(channelId)}/files/${encodeURIComponent(fileId)}/thumbnail`;
}

// ---- API tokens ----

export function createToken(name: string, scope: 'read' | 'write'): Promise<TokenCreatedDto> {
  return json<TokenCreatedDto>('POST', '/api/tokens', { name, scope });
}

export function listTokens(): Promise<{ items: TokenDto[] }> {
  return json<{ items: TokenDto[] }>('GET', '/api/tokens');
}

export function revokeToken(tokenId: string): Promise<void> {
  return empty('DELETE', `/api/tokens/${encodeURIComponent(tokenId)}`);
}
