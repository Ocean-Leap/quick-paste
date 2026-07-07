// Tokened API client for a quick-paste instance. Every call sends
// Authorization: Bearer <token> — no cookies, so no CSRF (AuthFilter exempts
// bearer). With MV3 host_permissions granted for the instance origin, these
// cross-origin fetches are not subject to CORS.

import type {
  ApiErrorEnvelope,
  ClientConfigDto,
  ClipDto,
  ClipListResponse,
  FileUploadedDto,
  MeResponse,
} from './types';

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

export function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : 'Something went wrong.';
}

export class QuickPasteClient {
  constructor(
    private readonly serverUrl: string,
    private readonly token: string,
  ) {}

  private authHeader(): Record<string, string> {
    return { Authorization: `Bearer ${this.token}` };
  }

  private async request(method: string, path: string, body?: unknown): Promise<Response> {
    const headers: Record<string, string> = { ...this.authHeader() };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const res = await fetch(this.serverUrl + path, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    if (!res.ok && res.status !== 204) {
      throw await this.toError(res);
    }
    return res;
  }

  private async toError(res: Response): Promise<ApiError> {
    let code = 'unknown';
    let message = `Request failed (${res.status})`;
    try {
      const body = (await res.json()) as Partial<ApiErrorEnvelope>;
      if (body.error) {
        code = body.error.code;
        message = body.error.message;
      }
    } catch {
      // non-JSON body — keep the generic message
    }
    return new ApiError(res.status, code, message);
  }

  async me(): Promise<MeResponse> {
    return (await this.request('GET', '/api/auth/me')).json() as Promise<MeResponse>;
  }

  async config(): Promise<ClientConfigDto> {
    return (await this.request('GET', '/api/config')).json() as Promise<ClientConfigDto>;
  }

  async listClips(channelId: string, opts: { beforeId?: string; limit?: number } = {}): Promise<ClipListResponse> {
    const params = new URLSearchParams();
    if (opts.beforeId) params.set('beforeId', opts.beforeId);
    params.set('limit', String(opts.limit ?? 20));
    const query = params.toString();
    const res = await this.request('GET', `/api/channels/${enc(channelId)}/clips?${query}`);
    return res.json() as Promise<ClipListResponse>;
  }

  async createTextClip(channelId: string, text: string, origin: string): Promise<ClipDto> {
    const res = await this.request('POST', `/api/channels/${enc(channelId)}/clips`, {
      type: 'text',
      text,
      origin,
      idempotencyKey: crypto.randomUUID(),
    });
    return res.json() as Promise<ClipDto>;
  }

  async createFileClip(channelId: string, fileId: string, origin: string): Promise<ClipDto> {
    const res = await this.request('POST', `/api/channels/${enc(channelId)}/clips`, {
      type: 'file',
      fileId,
      origin,
      idempotencyKey: crypto.randomUUID(),
    });
    return res.json() as Promise<ClipDto>;
  }

  /** Simple multipart upload (≤ simpleUploadMb). Larger files must use the app. */
  async uploadFile(channelId: string, file: Blob, filename: string): Promise<FileUploadedDto> {
    const form = new FormData();
    form.append('file', file, filename);
    const res = await fetch(`${this.serverUrl}/api/channels/${enc(channelId)}/files`, {
      method: 'POST',
      headers: this.authHeader(),
      body: form,
    });
    if (!res.ok) {
      throw await this.toError(res);
    }
    return res.json() as Promise<FileUploadedDto>;
  }

  /** File bytes with the auth header (bearer can't ride on <img src>/<a href>). */
  async fetchFileBlob(channelId: string, fileId: string): Promise<Blob> {
    const res = await fetch(`${this.serverUrl}/api/channels/${enc(channelId)}/files/${enc(fileId)}`, {
      headers: this.authHeader(),
    });
    if (!res.ok) {
      throw await this.toError(res);
    }
    return res.blob();
  }

  async fetchThumbnailBlob(channelId: string, fileId: string): Promise<Blob> {
    const res = await fetch(
      `${this.serverUrl}/api/channels/${enc(channelId)}/files/${enc(fileId)}/thumbnail`,
      { headers: this.authHeader() },
    );
    if (!res.ok) {
      throw await this.toError(res);
    }
    return res.blob();
  }
}

function enc(segment: string): string {
  return encodeURIComponent(segment);
}
