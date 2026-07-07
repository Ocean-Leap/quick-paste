import { useEffect, useRef, useState } from 'react';
import { logout } from '../api/client';
import type { ChannelDto, UserDto } from '../api/types';
import type { ConnectionStatus } from '../ws/useStream';
import { ChannelSwitcher } from './ChannelSwitcher';
import { ChevronDownIcon, UsersIcon } from './icons';

interface TopBarProps {
  user: UserDto;
  channels: ChannelDto[];
  selected: ChannelDto | null;
  connection: ConnectionStatus;
  onSelect: (channelId: string) => void;
  onChannelCreated: (channel: ChannelDto) => void;
  onToggleChannelDetail: (() => void) | null;
  onOpenSettings: () => void;
}

const CONN_LABEL: Record<ConnectionStatus, string> = {
  open: 'live',
  connecting: 'connecting…',
  reconnecting: 'reconnecting…',
};

export function TopBar({
  user,
  channels,
  selected,
  connection,
  onSelect,
  onChannelCreated,
  onToggleChannelDetail,
  onOpenSettings,
}: TopBarProps) {
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!menuOpen) return;
    const onPointerDown = (event: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) setMenuOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMenuOpen(false);
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [menuOpen]);

  const handleSignOut = async () => {
    try {
      await logout();
    } catch {
      // Session may already be gone — continue to the sign-in screen.
    }
    location.href = '/';
  };

  return (
    <header className="topbar">
      <span className="wordmark">quick-paste</span>
      <ChannelSwitcher
        channels={channels}
        selected={selected}
        onSelect={onSelect}
        onCreated={onChannelCreated}
      />
      {onToggleChannelDetail && (
        <button
          type="button"
          className="icon-btn"
          aria-label="Channel members and settings"
          onClick={onToggleChannelDetail}
        >
          <UsersIcon size={18} />
        </button>
      )}
      <div className="spacer" />
      <div className="dropdown" ref={menuRef}>
        <button
          type="button"
          className="btn dropdown-toggle"
          aria-haspopup="menu"
          aria-expanded={menuOpen}
          onClick={() => setMenuOpen((v) => !v)}
        >
          <span className="dropdown-label">{user.displayName || user.email}</span>
          <span className="user-initial" aria-hidden="true">
            {(user.displayName || user.email).charAt(0).toUpperCase()}
          </span>
          <ChevronDownIcon size={14} />
        </button>
        {menuOpen && (
          <ul className="dropdown-menu dropdown-menu-right" role="menu" aria-label="Account">
            <li className="menu-static" role="presentation">
              {user.email}
            </li>
            <li className="menu-separator" role="presentation" />
            <li role="presentation">
              <button
                type="button"
                role="menuitem"
                className="menu-item"
                onClick={() => {
                  setMenuOpen(false);
                  onOpenSettings();
                }}
              >
                Account settings
              </button>
            </li>
            <li role="presentation">
              <button
                type="button"
                role="menuitem"
                className="menu-item"
                onClick={() => void handleSignOut()}
              >
                Sign out
              </button>
            </li>
          </ul>
        )}
      </div>
      <span className={`conn conn-${connection}`} role="status" aria-label={CONN_LABEL[connection]}>
        <span className="conn-dot" aria-hidden="true" />
        <span className="conn-word">{CONN_LABEL[connection]}</span>
      </span>
    </header>
  );
}
