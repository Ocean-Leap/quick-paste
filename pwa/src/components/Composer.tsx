import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createClip, errorMessage } from '../api/client';
import type { ChannelDto } from '../api/types';
import { getDeviceLabel } from '../lib/device';
import { formatBytes } from '../lib/format';
import { uploadFile, uploadFileResumable } from '../lib/upload';
import { useAppStore } from '../state/store';
import { ClipboardPasteIcon, CloseIcon, FileIcon } from './icons';
import { randomId } from '../lib/uuid';

const MB = 1024 * 1024;

/** A file waiting in the composer. Nothing uploads until the user hits Send. */
interface Attachment {
  key: string;
  file: File;
  /** Object URL for image previews; revoked on unstage/unmount. */
  previewUrl: string | null;
  progress: number;
  status: 'staged' | 'uploading' | 'error';
  error: string | null;
}

/** Payload handed over from a Web Share Target intake (see Shell). */
export interface IncomingShare {
  text: string;
  files: File[];
}

/** Truncate a filename in the middle so the extension stays visible. */
function truncateMiddle(name: string, max = 28): string {
  if (name.length <= max) return name;
  const head = Math.ceil((max - 1) * 0.6);
  const tail = max - 1 - head;
  return `${name.slice(0, head)}…${name.slice(name.length - tail)}`;
}

export function Composer({
  channel,
  share,
  onShareConsumed,
  onToast,
}: {
  channel: ChannelDto;
  share: IncomingShare | null;
  onShareConsumed: () => void;
  onToast: (message: string) => void;
}) {
  const { state, dispatch } = useAppStore();
  const { config } = state;
  const [text, setText] = useState('');
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [dragging, setDragging] = useState(false);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  // Autofocus the textarea on desktop only — on touch it would raise the
  // keyboard over the content the user came to read.
  useEffect(() => {
    if (window.matchMedia('(min-width: 900px)').matches) textareaRef.current?.focus();
  }, [channel.id]);

  const textBytes = useMemo(() => new TextEncoder().encode(text).length, [text]);
  const tooLarge = textBytes > config.maxTextKb * 1024;

  const patchAttachment = (key: string, patch: Partial<Attachment>) => {
    setAttachments((prev) => prev.map((a) => (a.key === key ? { ...a, ...patch } : a)));
  };

  // The async send loop and unmount cleanup need the latest list, not the
  // one captured when the closure was created.
  const attachmentsRef = useRef<Attachment[]>([]);
  useEffect(() => {
    attachmentsRef.current = attachments;
  }, [attachments]);
  useEffect(
    () => () => {
      for (const a of attachmentsRef.current) {
        if (a.previewUrl) URL.revokeObjectURL(a.previewUrl);
      }
    },
    [],
  );

  const unstage = (key: string) => {
    const att = attachmentsRef.current.find((a) => a.key === key);
    if (att?.previewUrl) URL.revokeObjectURL(att.previewUrl);
    setAttachments((prev) => prev.filter((a) => a.key !== key));
  };

  /** Stages files as chips; oversize files are rejected inline, not sent. */
  const stageFiles = useCallback(
    (files: File[]) => {
      if (files.length === 0) return;
      setAttachments((prev) => [
        ...prev,
        ...files.map((file): Attachment => {
          const oversize = file.size > config.maxFileMb * MB;
          return {
            key: randomId(),
            file,
            previewUrl: file.type.startsWith('image/') ? URL.createObjectURL(file) : null,
            progress: 0,
            status: oversize ? 'error' : 'staged',
            error: oversize ? `Too large — files are limited to ${config.maxFileMb} MB.` : null,
          };
        }),
      ]);
    },
    [config.maxFileMb],
  );

  // Consume a Web Share Target payload exactly once: text prefills the
  // textarea, files land as staged attachments; the user confirms with Send.
  const consumedShareRef = useRef<IncomingShare | null>(null);
  useEffect(() => {
    if (!share || consumedShareRef.current === share) return;
    consumedShareRef.current = share;
    if (share.text) {
      setText((prev) => (prev.trim() ? `${prev}\n${share.text}` : share.text));
      textareaRef.current?.focus();
    }
    if (share.files.length) stageFiles(share.files);
    onShareConsumed();
  });

  // Dropping files anywhere on the window stages them; while a drag is in
  // flight the whole composer lights up as the target.
  useEffect(() => {
    let depth = 0;
    const hasFiles = (event: DragEvent) =>
      Array.from(event.dataTransfer?.types ?? []).includes('Files');
    const onDragEnter = (event: DragEvent) => {
      if (!hasFiles(event)) return;
      event.preventDefault();
      depth += 1;
      setDragging(true);
    };
    const onDragOver = (event: DragEvent) => {
      if (hasFiles(event)) event.preventDefault();
    };
    const onDragLeave = (event: DragEvent) => {
      if (!hasFiles(event)) return;
      depth = Math.max(0, depth - 1);
      if (depth === 0) setDragging(false);
    };
    const onDrop = (event: DragEvent) => {
      depth = 0;
      setDragging(false);
      if (!hasFiles(event)) return;
      event.preventDefault();
      const files = Array.from(event.dataTransfer?.files ?? []);
      if (files.length) stageFiles(files);
    };
    window.addEventListener('dragenter', onDragEnter);
    window.addEventListener('dragover', onDragOver);
    window.addEventListener('dragleave', onDragLeave);
    window.addEventListener('drop', onDrop);
    return () => {
      window.removeEventListener('dragenter', onDragEnter);
      window.removeEventListener('dragover', onDragOver);
      window.removeEventListener('dragleave', onDragLeave);
      window.removeEventListener('drop', onDrop);
    };
  }, [stageFiles]);

  /** Uploads one staged attachment and creates its clip; keeps the chip with
   * an inline error on failure so nothing is lost. */
  const sendAttachment = async (att: Attachment): Promise<void> => {
    patchAttachment(att.key, { status: 'uploading', progress: 0, error: null });
    try {
      // Small files go up in one multipart POST; big ones use resumable tus.
      const upload = att.file.size <= config.simpleUploadMb * MB ? uploadFile : uploadFileResumable;
      const uploaded = await upload(channel.id, att.file, (fraction) =>
        patchAttachment(att.key, { progress: fraction }),
      );
      const clip = await createClip(channel.id, {
        type: 'file',
        fileId: uploaded.fileId,
        origin: getDeviceLabel(),
        idempotencyKey: randomId(),
      });
      dispatch({ type: 'clip-upserted', channelId: channel.id, clip });
      unstage(att.key);
    } catch (err) {
      patchAttachment(att.key, { status: 'error', error: errorMessage(err) });
    }
  };

  const stagedCount = attachments.filter((a) => a.status === 'staged').length;
  const canSend = !sending && !tooLarge && (Boolean(text.trim()) || stagedCount > 0);

  // One send for everything: the text clip first (if any), then each staged
  // attachment sequentially. A failed text send stops before the files; a
  // failed file keeps its chip (with the error) and the loop moves on.
  const handleSend = async () => {
    if (!canSend) return;
    setSending(true);
    setSendError(null);
    try {
      const sent = text;
      if (sent.trim()) {
        try {
          const clip = await createClip(channel.id, {
            type: 'text',
            text: sent,
            origin: getDeviceLabel(),
            idempotencyKey: randomId(),
          });
          dispatch({ type: 'clip-upserted', channelId: channel.id, clip });
          // Only clear if the user hasn't typed something else in the meantime.
          setText((prev) => (prev === sent ? '' : prev));
        } catch (err) {
          setSendError(errorMessage(err));
          return;
        }
      }
      const toSend = attachmentsRef.current.filter((a) => a.status === 'staged');
      for (const att of toSend) await sendAttachment(att);
    } finally {
      setSending(false);
    }
  };

  // Oversize text becomes a staged clip.txt — sent with everything else. The
  // content lives on in the chip, so clearing the textarea loses nothing.
  const stageTextAsFile = () => {
    const staged = text;
    stageFiles([new File([staged], 'clip.txt', { type: 'text/plain' })]);
    setText((prev) => (prev === staged ? '' : prev));
  };

  const appendText = (incoming: string) => {
    setText((prev) => (prev.trim() ? `${prev}\n${incoming}` : incoming));
    textareaRef.current?.focus();
  };

  // Reading the clipboard needs a user gesture — this runs from the paste
  // button click. read() gives files + text; readText() is the fallback for
  // engines without read(); absent both (http origins), point at Ctrl+V.
  const handlePasteClick = async () => {
    const clipboard: Partial<Clipboard> | undefined = navigator.clipboard;
    try {
      if (clipboard && typeof clipboard.read === 'function') {
        const items = await clipboard.read();
        const files: File[] = [];
        let pastedText = '';
        for (const item of items) {
          const fileType =
            item.types.find((t) => t.startsWith('image/')) ??
            item.types.find((t) => !t.startsWith('text/'));
          if (fileType) {
            const blob = await item.getType(fileType);
            const ext = fileType.split('/')[1]?.split('+')[0] ?? 'bin';
            files.push(new File([blob], `pasted-${Date.now()}.${ext}`, { type: fileType }));
            continue;
          }
          if (item.types.includes('text/plain')) {
            const blob = await item.getType('text/plain');
            const value = await blob.text();
            if (value) pastedText = pastedText ? `${pastedText}\n${value}` : value;
          }
        }
        if (files.length) stageFiles(files);
        if (pastedText) appendText(pastedText);
        return;
      }
    } catch {
      // read() denied or item decoding failed — try the text-only fallback.
    }
    try {
      if (clipboard && typeof clipboard.readText === 'function') {
        const value = await clipboard.readText();
        if (value) appendText(value);
        return;
      }
    } catch {
      // Denied too — fall through to the hint.
    }
    onToast('Clipboard blocked — press Ctrl+V / ⌘V instead');
  };

  return (
    <section
      className={dragging ? 'composer card dragging' : 'composer card'}
      aria-label="Send something"
    >
      <div className="composer-input">
        <textarea
          ref={textareaRef}
          value={text}
          rows={3}
          placeholder="Paste anything…"
          aria-label="New text clip — Enter to send, Shift+Enter for a newline"
          onChange={(event) => setText(event.target.value)}
          onPaste={(event) => {
            // Pasting files (screenshots!) into the textarea stages them —
            // text pastes fall through to the default behavior.
            const files = Array.from(event.clipboardData.files);
            if (files.length > 0) {
              event.preventDefault();
              stageFiles(files);
            }
          }}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault();
              void handleSend();
            }
          }}
        />
        <button
          type="button"
          className="icon-btn paste-btn"
          aria-label="Paste from clipboard"
          onClick={() => void handlePasteClick()}
        >
          <ClipboardPasteIcon size={16} />
        </button>
      </div>
      <p className="composer-hint" aria-hidden="true">
        enter to send · shift+enter for a newline · drop or paste files anywhere
      </p>
      {tooLarge && (
        <p className="hint">
          This text is larger than {config.maxTextKb} KB — attach it as a file instead.{' '}
          <button type="button" className="btn small" onClick={stageTextAsFile}>
            Attach as file
          </button>
        </p>
      )}
      {attachments.length > 0 && (
        <ul className="attachment-list" aria-label="Staged attachments">
          {attachments.map((att) => (
            <li key={att.key} className={att.error ? 'attachment-chip has-error' : 'attachment-chip'}>
              {att.previewUrl ? (
                <img className="chip-thumb" src={att.previewUrl} alt="" />
              ) : (
                <span className="chip-icon">
                  <FileIcon size={18} />
                </span>
              )}
              <span className="chip-body">
                <span className="chip-name" title={att.file.name}>
                  {truncateMiddle(att.file.name)}
                </span>
                {att.status === 'uploading' ? (
                  <progress
                    value={att.progress}
                    max={1}
                    aria-label={`Uploading ${att.file.name}`}
                  />
                ) : att.error ? (
                  <span className="chip-error">{att.error}</span>
                ) : (
                  <span className="chip-size">{formatBytes(att.file.size)}</span>
                )}
              </span>
              <button
                type="button"
                className="icon-btn chip-remove"
                aria-label={`Remove ${att.file.name}`}
                disabled={att.status === 'uploading'}
                onClick={() => unstage(att.key)}
              >
                <CloseIcon size={14} />
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="composer-row">
        <button
          type="button"
          className="btn primary"
          disabled={!canSend}
          onClick={() => void handleSend()}
        >
          {sending ? 'Sending…' : 'Send'}
        </button>
        <span className="spacer" />
        <button type="button" className="btn" onClick={() => fileInputRef.current?.click()}>
          Attach files
        </button>
        <input
          ref={fileInputRef}
          type="file"
          multiple
          hidden
          aria-label="Attach files"
          onChange={(event) => {
            const files = Array.from(event.target.files ?? []);
            event.target.value = '';
            if (files.length) stageFiles(files);
          }}
        />
      </div>
      {sendError && <p className="error">{sendError}</p>}
    </section>
  );
}
