import { useEffect, useRef, useState } from 'react';
import { createChannel, createExternalChannel, errorMessage } from '../api/client';
import type { ChannelDto } from '../api/types';
import { ChevronDownIcon } from './icons';

type CreateMode = 'menu' | 'shared' | 'steward';

interface ChannelSwitcherProps {
  channels: ChannelDto[];
  selected: ChannelDto | null;
  onSelect: (channelId: string) => void;
  onCreated: (channel: ChannelDto) => void;
}

export function ChannelSwitcher({ channels, selected, onSelect, onCreated }: ChannelSwitcherProps) {
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<CreateMode>('menu');
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [sharedName, setSharedName] = useState('');
  const [stewardName, setStewardName] = useState('Steward Mind');
  const [stewardUrl, setStewardUrl] = useState('');
  const [stewardToken, setStewardToken] = useState('');
  const [stewardTags, setStewardTags] = useState('');

  const rootRef = useRef<HTMLDivElement | null>(null);
  const sharedNameRef = useRef<HTMLInputElement | null>(null);
  const stewardTokenRef = useRef<HTMLInputElement | null>(null);

  const closeMenu = () => {
    setOpen(false);
    setMode('menu');
    setError(null);
  };

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) closeMenu();
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closeMenu();
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  useEffect(() => {
    if (!open) return;
    if (mode === 'shared') sharedNameRef.current?.focus();
    if (mode === 'steward') stewardTokenRef.current?.focus();
  }, [open, mode]);

  const handleCreateShared = async () => {
    const name = sharedName.trim();
    if (!name || creating) return;
    setCreating(true);
    setError(null);
    try {
      const channel = await createChannel(name);
      onCreated(channel);
      setSharedName('');
      closeMenu();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setCreating(false);
    }
  };

  const handleCreateSteward = async () => {
    if (creating) return;
    setCreating(true);
    setError(null);
    try {
      const tags = stewardTags
        .split(',')
        .map((t) => t.trim())
        .filter(Boolean);
      const channel = await createExternalChannel({
        name: stewardName.trim() || 'Steward Mind',
        type: 'external',
        provider: 'steward',
        baseUrl: stewardUrl.trim(),
        token: stewardToken.trim(),
        defaultTags: tags.length ? tags : undefined,
      });
      onCreated(channel);
      setStewardToken('');
      closeMenu();
    } catch (err) {
      setError(errorMessage(err));
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
        onClick={() => {
          if (open) closeMenu();
          else setOpen(true);
        }}
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
                  closeMenu();
                }}
              >
                <span className="channel-name">{channel.name}</span>
                <span className="badge">{channel.type}</span>
              </button>
            </li>
          ))}
          <li className="menu-separator" role="presentation" />
          {mode === 'menu' && (
            <>
              <li role="presentation">
                <button
                  type="button"
                  className="menu-item"
                  disabled={creating}
                  onClick={() => {
                    setError(null);
                    setMode('shared');
                  }}
                >
                  New shared channel
                </button>
              </li>
              <li role="presentation">
                <button
                  type="button"
                  className="menu-item"
                  disabled={creating}
                  onClick={() => {
                    setError(null);
                    setMode('steward');
                  }}
                >
                  New Steward Mind channel
                </button>
              </li>
            </>
          )}
          {mode === 'shared' && (
            <li className="menu-form" role="presentation">
              <form
                className="channel-create-form"
                onSubmit={(event) => {
                  event.preventDefault();
                  void handleCreateShared();
                }}
              >
                <label>
                  Channel name
                  <input
                    ref={sharedNameRef}
                    value={sharedName}
                    onChange={(e) => setSharedName(e.target.value)}
                    maxLength={64}
                    placeholder="family, work…"
                    required
                  />
                </label>
                {error && <p className="error">{error}</p>}
                <div className="channel-create-actions">
                  <button
                    type="button"
                    className="btn small"
                    onClick={() => {
                      setMode('menu');
                      setError(null);
                    }}
                  >
                    Back
                  </button>
                  <button
                    type="submit"
                    className="btn primary small"
                    disabled={creating || !sharedName.trim()}
                  >
                    {creating ? 'Creating…' : 'Create'}
                  </button>
                </div>
              </form>
            </li>
          )}
          {mode === 'steward' && (
            <li className="menu-form" role="presentation">
              <form
                className="channel-create-form"
                onSubmit={(event) => {
                  event.preventDefault();
                  void handleCreateSteward();
                }}
              >
                <label>
                  Name
                  <input
                    value={stewardName}
                    onChange={(e) => setStewardName(e.target.value)}
                    maxLength={64}
                  />
                </label>
                <label>
                  Steward URL
                  <input
                    value={stewardUrl}
                    onChange={(e) => setStewardUrl(e.target.value)}
                    placeholder="https://your-steward.example"
                    required
                  />
                </label>
                <label>
                  API token
                  <input
                    ref={stewardTokenRef}
                    type="password"
                    value={stewardToken}
                    onChange={(e) => setStewardToken(e.target.value)}
                    placeholder="stw_…"
                    autoComplete="off"
                    required
                  />
                </label>
                <label>
                  Default tags (optional)
                  <input
                    value={stewardTags}
                    onChange={(e) => setStewardTags(e.target.value)}
                    placeholder="reading, work"
                  />
                </label>
                {error && <p className="error">{error}</p>}
                <div className="channel-create-actions">
                  <button
                    type="button"
                    className="btn small"
                    onClick={() => {
                      setMode('menu');
                      setError(null);
                    }}
                  >
                    Back
                  </button>
                  <button
                    type="submit"
                    className="btn primary small"
                    disabled={creating || !stewardToken.trim() || !stewardUrl.trim()}
                  >
                    {creating ? 'Creating…' : 'Create'}
                  </button>
                </div>
              </form>
            </li>
          )}
        </ul>
      )}
    </div>
  );
}
