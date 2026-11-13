import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import {
  deleteChannel,
  errorMessage,
  inviteMember,
  listMembers,
  patchChannelSink,
  removeMember,
} from '../api/client';
import type { ChannelDto, MemberDto } from '../api/types';
import { useAppStore } from '../state/store';
import { ConfirmDialog } from './ConfirmDialog';
import { CloseIcon } from './icons';

type PendingConfirm =
  | { kind: 'remove'; member: MemberDto }
  | { kind: 'leave' }
  | { kind: 'delete' };

export function ChannelDetail({ channel, onClose }: { channel: ChannelDto; onClose: () => void }) {
  const { state, dispatch } = useAppStore();
  const [members, setMembers] = useState<MemberDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [inviteEmail, setInviteEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const [token, setToken] = useState('');
  const [baseUrl, setBaseUrl] = useState(channel.sink?.baseUrl ?? '');
  const [confirm, setConfirm] = useState<PendingConfirm | null>(null);
  const [confirmBusy, setConfirmBusy] = useState(false);

  const isExternal = channel.type === 'external';
  const isShared = channel.type === 'shared';

  useEffect(() => {
    if (isExternal) {
      setMembers([]);
      setBaseUrl(channel.sink?.baseUrl ?? '');
      return;
    }
    let cancelled = false;
    setMembers(null);
    setError(null);
    listMembers(channel.id)
      .then((response) => {
        if (!cancelled) setMembers(response.items);
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err));
      });
    return () => {
      cancelled = true;
    };
  }, [channel.id, channel.sink?.baseUrl, isExternal]);

  const isOwner = channel.role === 'owner';
  const selfMember = members?.find((m) => m.userId === state.user.id) ?? null;

  const handleInvite = async (event: FormEvent) => {
    event.preventDefault();
    const email = inviteEmail.trim();
    if (!email || busy) return;
    setBusy(true);
    setError(null);
    try {
      const member = await inviteMember(channel.id, email);
      setMembers((prev) => (prev ? [...prev, member] : [member]));
      setInviteEmail('');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const runConfirmed = async () => {
    if (!confirm || confirmBusy) return;
    setConfirmBusy(true);
    setError(null);
    try {
      if (confirm.kind === 'remove') {
        await removeMember(channel.id, confirm.member.id);
        setMembers((prev) => (prev ? prev.filter((m) => m.id !== confirm.member.id) : prev));
      } else if (confirm.kind === 'leave') {
        if (!selfMember) return;
        await removeMember(channel.id, selfMember.id);
        dispatch({ type: 'channel-removed', channelId: channel.id });
        setConfirm(null);
        onClose();
        return;
      } else {
        await deleteChannel(channel.id);
        dispatch({ type: 'channel-removed', channelId: channel.id });
        setConfirm(null);
        onClose();
        return;
      }
      setConfirm(null);
    } catch (err) {
      setError(errorMessage(err));
      setConfirm(null);
    } finally {
      setConfirmBusy(false);
    }
  };

  const handleRotateSink = async (event: FormEvent) => {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const updated = await patchChannelSink(channel.id, {
        token: token.trim() || undefined,
        baseUrl: baseUrl.trim() || undefined,
      });
      dispatch({
        type: 'channels-resynced',
        channels: state.channels.map((c) => (c.id === updated.id ? updated : c)),
      });
      setToken('');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const confirmProps = (() => {
    if (!confirm) return null;
    if (confirm.kind === 'remove') {
      const label = confirm.member.status === 'pending' ? 'Cancel invite' : 'Remove member';
      return {
        title: label,
        message:
          confirm.member.status === 'pending'
            ? `Cancel the invite for ${confirm.member.email}?`
            : `Remove ${confirm.member.email} from this channel?`,
        confirmLabel: label,
      };
    }
    if (confirm.kind === 'leave') {
      return {
        title: 'Leave channel',
        message: `Leave "${channel.name}"?`,
        confirmLabel: 'Leave',
      };
    }
    return {
      title: 'Delete channel',
      message: isExternal
        ? `Delete "${channel.name}"? Local history will be removed; Steward items stay in Mind.`
        : `Delete "${channel.name}" for everyone? All items will be removed.`,
      confirmLabel: 'Delete',
    };
  })();

  return (
    <section className="channel-detail card" aria-label={`Channel ${channel.name}`}>
      <header className="panel-header">
        <h2>{channel.name}</h2>
        <button type="button" className="icon-btn" aria-label="Close channel panel" onClick={onClose}>
          <CloseIcon size={16} />
        </button>
      </header>
      {error && <p className="error">{error}</p>}

      {isExternal && channel.sink && (
        <div className="sink-panel">
          <p className="muted">
            Outbound Steward Mind sink · token {channel.sink.tokenPrefix}…
            {channel.sink.defaultTags?.length
              ? ` · tags ${channel.sink.defaultTags.join(', ')}`
              : ''}
          </p>
          <form className="invite-form sink-form" onSubmit={(e) => void handleRotateSink(e)}>
            <input
              type="url"
              value={baseUrl}
              placeholder="https://steward.example"
              aria-label="Steward base URL"
              onChange={(e) => setBaseUrl(e.target.value)}
            />
            <input
              type="password"
              value={token}
              placeholder="New stw_ token (optional)"
              aria-label="Rotate Steward token"
              autoComplete="off"
              onChange={(e) => setToken(e.target.value)}
            />
            <button
              type="submit"
              className="btn"
              disabled={busy || (!token.trim() && baseUrl.trim() === (channel.sink?.baseUrl ?? ''))}
            >
              {busy ? 'Saving…' : 'Update sink'}
            </button>
          </form>
        </div>
      )}

      {isShared &&
        (members === null ? (
          <p className="muted">Loading members…</p>
        ) : (
          <ul className="member-list">
            {members.map((member) => (
              <li key={member.id} className="member-row">
                <div className="member-info">
                  <span>{member.displayName || member.email}</span>
                  <span className="muted">{member.email}</span>
                </div>
                <span className="badge">{member.role}</span>
                {member.status === 'pending' && <span className="badge pending">pending</span>}
                {isOwner && member.userId !== state.user.id && (
                  <button
                    type="button"
                    className="btn danger-ghost small"
                    onClick={() => setConfirm({ kind: 'remove', member })}
                  >
                    Remove
                  </button>
                )}
              </li>
            ))}
          </ul>
        ))}
      {isShared && isOwner && (
        <form className="invite-form" onSubmit={(event) => void handleInvite(event)}>
          <input
            type="email"
            value={inviteEmail}
            required
            placeholder="Invite by email"
            aria-label="Invite by email"
            onChange={(event) => setInviteEmail(event.target.value)}
          />
          <button type="submit" className="btn" disabled={busy || !inviteEmail.trim()}>
            {busy ? 'Inviting…' : 'Invite'}
          </button>
        </form>
      )}
      <div className="panel-footer">
        {isOwner ? (
          <button type="button" className="btn danger" onClick={() => setConfirm({ kind: 'delete' })}>
            Delete channel
          </button>
        ) : (
          <button
            type="button"
            className="btn danger-ghost"
            disabled={!selfMember}
            onClick={() => setConfirm({ kind: 'leave' })}
          >
            Leave channel
          </button>
        )}
      </div>

      {confirmProps && (
        <ConfirmDialog
          open
          title={confirmProps.title}
          message={confirmProps.message}
          confirmLabel={confirmProps.confirmLabel}
          danger
          busy={confirmBusy}
          onCancel={() => {
            if (!confirmBusy) setConfirm(null);
          }}
          onConfirm={() => void runConfirmed()}
        />
      )}
    </section>
  );
}
