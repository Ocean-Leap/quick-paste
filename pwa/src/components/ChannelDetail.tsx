import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { deleteChannel, errorMessage, inviteMember, listMembers, removeMember } from '../api/client';
import type { ChannelDto, MemberDto } from '../api/types';
import { useAppStore } from '../state/store';
import { CloseIcon } from './icons';

export function ChannelDetail({ channel, onClose }: { channel: ChannelDto; onClose: () => void }) {
  const { state, dispatch } = useAppStore();
  const [members, setMembers] = useState<MemberDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [inviteEmail, setInviteEmail] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
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
  }, [channel.id]);

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

  const handleRemove = async (member: MemberDto) => {
    const label = member.status === 'pending' ? 'Cancel the invite for' : 'Remove';
    if (!window.confirm(`${label} ${member.email}?`)) return;
    setError(null);
    try {
      await removeMember(channel.id, member.id);
      setMembers((prev) => (prev ? prev.filter((m) => m.id !== member.id) : prev));
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  const handleLeave = async () => {
    if (!selfMember) return;
    if (!window.confirm(`Leave "${channel.name}"?`)) return;
    setError(null);
    try {
      await removeMember(channel.id, selfMember.id);
      dispatch({ type: 'channel-removed', channelId: channel.id });
      onClose();
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  const handleDelete = async () => {
    if (!window.confirm(`Delete "${channel.name}" for everyone? All items will be removed.`)) return;
    setError(null);
    try {
      await deleteChannel(channel.id);
      dispatch({ type: 'channel-removed', channelId: channel.id });
      onClose();
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  return (
    <section className="channel-detail card" aria-label={`Channel ${channel.name}`}>
      <header className="panel-header">
        <h2>{channel.name}</h2>
        <button type="button" className="icon-btn" aria-label="Close channel panel" onClick={onClose}>
          <CloseIcon size={16} />
        </button>
      </header>
      {error && <p className="error">{error}</p>}
      {members === null ? (
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
                  onClick={() => void handleRemove(member)}
                >
                  Remove
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {isOwner && (
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
          <button type="button" className="btn danger" onClick={() => void handleDelete()}>
            Delete channel
          </button>
        ) : (
          <button
            type="button"
            className="btn danger-ghost"
            disabled={!selfMember}
            onClick={() => void handleLeave()}
          >
            Leave channel
          </button>
        )}
      </div>
    </section>
  );
}
