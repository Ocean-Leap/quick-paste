import { useState } from 'react';
import { fileThumbnailUrl } from '../api/client';
import type { FileRefDto } from '../api/types';
import { useAppStore } from '../state/store';
import { FileIcon } from './icons';

/**
 * Server-rendered JPEG thumbnail for image files, falling back to the generic
 * file icon when thumbnails are disabled, the mime is not an image, or the
 * thumbnail request fails. Key usages by file id so the error state resets.
 */
export function FileThumbnail({
  channelId,
  file,
  className,
  iconSize,
}: {
  channelId: string;
  file: FileRefDto;
  className: string;
  iconSize: number;
}) {
  const { state } = useAppStore();
  const [failed, setFailed] = useState(false);

  if (!state.config.thumbnailsEnabled || !file.mime.startsWith('image/') || failed) {
    return <FileIcon size={iconSize} />;
  }
  return (
    <img
      className={className}
      loading="lazy"
      src={fileThumbnailUrl(channelId, file.id)}
      alt={file.name}
      onError={() => setFailed(true)}
    />
  );
}
