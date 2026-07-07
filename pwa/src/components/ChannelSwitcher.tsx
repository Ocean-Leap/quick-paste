import { useEffect, useRef, useState } from 'react';
import { createChannel, errorMessage } from '../api/client';
import type { ChannelDto } from '../api/types';
import { ChevronDownIcon } from './icons';

interface ChannelSwitcherProps {
  channels: ChannelDto[];
  selected: ChannelDto | null;
  onSelect: (channelId: string) => void;
  onCreated: (channel: ChannelDto) => void;
}

export function ChannelSwitcher({ channels, selected, onSelect, onCreated }: ChannelSwitcherProps) {
  const [open, setOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) setOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  const handleCreate = async () => {
    const name = window.prompt('Name for the new channel:');
    if (!name || !name.trim()) return;
    setCreating(true);
    try {
      const channel = await createChannel(name.trim());
      onCreated(channel);
      setOpen(false);
    } catch (err) {
      window.alert(errorMessage(err));
    } finally {
      setCreating(false);
    }
  };

  return (
    <div className="dropdown" ref={rootRef}>
      <button
        type="button"
        className="btn dropdown-toggle channel-toggle"
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        <span className="dropdown-label">{selected ? selected.name : 'No channel'}</span>
        {selected && <span className="badge">{selected.type}</span>}
        <ChevronDownIcon size={14} />
      </button>
      {open && (
        <ul className="dropdown-menu" role="listbox" aria-label="Channels">
          {channels.map((channel) => (
            <li key={channel.id} role="option" aria-selected={channel.id === selected?.id}>
              <button
                type="button"
                className={channel.id === selected?.id ? 'menu-item active' : 'menu-item'}
                onClick={() => {
                  onSelect(channel.id);
                  setOpen(false);
                }}
              >
                <span className="channel-name">{channel.name}</span>
                <span className="badge">{channel.type}</span>
              </button>
            </li>
          ))}
          <li className="menu-separator" role="presentation" />
          <li role="presentation">
            <button
              type="button"
              className="menu-item"
              disabled={creating}
              onClick={() => void handleCreate()}
            >
              {creating ? 'Creating…' : 'New channel'}
            </button>
          </li>
        </ul>
      )}
    </div>
  );
}
