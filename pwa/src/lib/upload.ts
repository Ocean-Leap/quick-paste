import * as tus from 'tus-js-client';
import { ApiError, csrfToken } from '../api/client';
import type { ApiErrorEnvelope, FileUploadedDto } from '../api/types';

const TUS_CHUNK_BYTES = 8 * 1024 * 1024;

function parseErrorEnvelope(status: number, body: string, fallback: string): ApiError {
  let code = 'unknown';
  let message = fallback;
  let details: Record<string, unknown> = {};
  try {
    const parsed = JSON.parse(body) as Partial<ApiErrorEnvelope>;
    if (parsed.error) {
      code = parsed.error.code;
      message = parsed.error.message;
      details = parsed.error.details ?? {};
    }
  } catch {
    // Keep the generic message.
  }
  return new ApiError(status, code, message, details);
}

/**
 * Uploads a file to a channel via multipart POST using XMLHttpRequest so we
 * can report upload progress (fraction 0..1).
 */
export function uploadFile(
  channelId: string,
  file: File,
  onProgress: (fraction: number) => void,
): Promise<FileUploadedDto> {
  return new Promise<FileUploadedDto>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `/api/channels/${encodeURIComponent(channelId)}/files`);
    xhr.withCredentials = true;
    const token = csrfToken();
    if (token) xhr.setRequestHeader('X-CSRF-Token', token);

    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && event.total > 0) {
        onProgress(event.loaded / event.total);
      }
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          resolve(JSON.parse(xhr.responseText) as FileUploadedDto);
        } catch {
          reject(new ApiError(xhr.status, 'unknown', 'Upload succeeded but the response was unreadable.'));
        }
        return;
      }
      reject(parseErrorEnvelope(xhr.status, xhr.responseText, `Upload failed (${xhr.status})`));
    };
    xhr.onerror = () => reject(new ApiError(0, 'network_error', 'Network error during upload.'));
    xhr.onabort = () => reject(new ApiError(0, 'aborted', 'Upload was cancelled.'));

    const form = new FormData();
    form.append('file', file, file.name);
    xhr.send(form);
  });
}

/**
 * Uploads a large file via the resumable tus flow (SPEC §8.2). The server
 * uses the upload id — the last segment of the tus Location URL — as the
 * fileId, so the result plugs into createClip exactly like a simple upload.
 */
export function uploadFileResumable(
  channelId: string,
  file: File,
  onProgress: (fraction: number) => void,
): Promise<FileUploadedDto> {
  const mime = file.type || 'application/octet-stream';
  // Read the CSRF token up front: an empty header would be rejected by the
  // AuthFilter with a 403 partway through the upload. Fail fast with a clear,
  // caller-handled error (same surface as the simple-upload path) so the staged
  // chip shows an actionable message instead of a confusing mid-upload 403.
  const token = csrfToken();
  if (!token) {
    return Promise.reject(
      new ApiError(
        0,
        'csrf_missing',
        'Your session needs a refresh — reload the page and try the upload again.',
      ),
    );
  }
  return new Promise<FileUploadedDto>((resolve, reject) => {
    const upload = new tus.Upload(file, {
      endpoint: `/api/channels/${encodeURIComponent(channelId)}/files/uploads`,
      chunkSize: TUS_CHUNK_BYTES,
      metadata: { filename: file.name, filetype: mime },
      headers: { 'X-CSRF-Token': token },
      removeFingerprintOnSuccess: true,
      onProgress: (bytesSent, bytesTotal) => {
        if (bytesTotal > 0) onProgress(bytesSent / bytesTotal);
      },
      onError: (err) => {
        if (err instanceof tus.DetailedError && err.originalResponse) {
          const status = err.originalResponse.getStatus();
          reject(
            parseErrorEnvelope(status, err.originalResponse.getBody(), `Upload failed (${status})`),
          );
          return;
        }
        reject(new ApiError(0, 'network_error', 'Network error during upload.'));
      },
      onSuccess: () => {
        const fileId = upload.url?.split('/').filter(Boolean).pop();
        if (!fileId) {
          reject(new ApiError(0, 'unknown', 'Upload finished but no upload URL was assigned.'));
          return;
        }
        resolve({ fileId, name: file.name, size: file.size, mime });
      },
    });
    upload.start();
  });
}
