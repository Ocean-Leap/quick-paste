import { useCallback, useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import {
  createToken,
  deleteAccount,
  errorMessage,
  exportDownloadUrl,
  getExportJob,
  listTokens,
  logoutAll,
  requestExport,
  revokeToken,
} from '../api/client';
import type { ExportJobDto, TokenCreatedDto, TokenDto, UserDto } from '../api/types';
import { copyText } from '../lib/clipboard';
import { getDeviceLabel, setDeviceLabel } from '../lib/device';
import { disablePush, enablePush, getPushState, pushSupported } from '../lib/push';
import type { PushState } from '../lib/push';
import { relativeTime, useNow } from '../lib/relativeTime';
import { useAppStore } from '../state/store';
import { CloseIcon } from './icons';

export function AccountSettings({ user, onClose }: { user: UserDto; onClose: () => void }) {
  const { state } = useAppStore();
  const { config } = state;
  const now = useNow();
  const closeRef = useRef<HTMLButtonElement | null>(null);

  const [device, setDevice] = useState(() => getDeviceLabel());
  const [deviceSaved, setDeviceSaved] = useState(false);

  const [pushState, setPushState] = useState<PushState | 'checking'>(() =>
    pushSupported() ? 'checking' : 'unsupported',
  );
  const [pushBusy, setPushBusy] = useState(false);
  const [pushError, setPushError] = useState<string | null>(null);

  const [tokens, setTokens] = useState<TokenDto[] | null>(null);
  const [tokenError, setTokenError] = useState<string | null>(null);
  const [tokenName, setTokenName] = useState('');
  const [tokenScope, setTokenScope] = useState<'read' | 'write'>('write');
  const [tokenBusy, setTokenBusy] = useState(false);
  const [createdToken, setCreatedToken] = useState<TokenCreatedDto | null>(null);
  const [secretCopied, setSecretCopied] = useState(false);

  const [exportJob, setExportJob] = useState<ExportJobDto | null>(null);
  const [exportError, setExportError] = useState<string | null>(null);
  const [exportBusy, setExportBusy] = useState(false);

  const [accountError, setAccountError] = useState<string | null>(null);

  useEffect(() => {
    closeRef.current?.focus();
  }, []);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  const refreshTokens = useCallback(async () => {
    try {
      const response = await listTokens();
      setTokens(response.items);
    } catch (err) {
      setTokenError(errorMessage(err));
    }
  }, []);

  useEffect(() => {
    void refreshTokens();
  }, [refreshTokens]);

  // Poll a pending/running export every 2 seconds until it finishes.
  useEffect(() => {
    if (!exportJob || exportJob.status === 'done' || exportJob.status === 'failed') return;
    const timer = window.setInterval(() => {
      getExportJob(exportJob.jobId)
        .then(setExportJob)
        .catch((err: unknown) => setExportError(errorMessage(err)));
    }, 2000);
    return () => window.clearInterval(timer);
  }, [exportJob]);

  // Reflect the current opt-in state without prompting (SPEC §10.7).
  useEffect(() => {
    if (!config.pushEnabled || !pushSupported()) return;
    let cancelled = false;
    getPushState()
      .then((value) => {
        if (!cancelled) setPushState(value);
      })
      .catch(() => {
        if (!cancelled) setPushState('disabled');
      });
    return () => {
      cancelled = true;
    };
  }, [config.pushEnabled]);

  const saveDevice = () => {
    setDeviceLabel(device);
    setDevice(getDeviceLabel());
    setDeviceSaved(true);
  };

  const handleEnablePush = async () => {
    if (!config.vapidPublicKey || pushBusy) return;
    setPushBusy(true);
    setPushError(null);
    try {
      setPushState(await enablePush(config.vapidPublicKey, getDeviceLabel()));
    } catch (err) {
      setPushError(errorMessage(err));
    } finally {
      setPushBusy(false);
    }
  };

  const handleDisablePush = async () => {
    if (pushBusy) return;
    setPushBusy(true);
    setPushError(null);
    try {
      setPushState(await disablePush());
    } catch (err) {
      setPushError(errorMessage(err));
    } finally {
      setPushBusy(false);
    }
  };

  const handleCreateToken = async (event: FormEvent) => {
    event.preventDefault();
    const name = tokenName.trim();
    if (!name || tokenBusy) return;
    setTokenBusy(true);
    setTokenError(null);
    try {
      const created = await createToken(name, tokenScope);
      setCreatedToken(created);
      setSecretCopied(false);
      setTokenName('');
      await refreshTokens();
    } catch (err) {
      setTokenError(errorMessage(err));
    } finally {
      setTokenBusy(false);
    }
  };

  const handleRevoke = async (token: TokenDto) => {
    if (!window.confirm(`Revoke token "${token.name}"?`)) return;
    setTokenError(null);
    try {
      await revokeToken(token.id);
      await refreshTokens();
    } catch (err) {
      setTokenError(errorMessage(err));
    }
  };

  const handleExport = async () => {
    setExportBusy(true);
    setExportError(null);
    try {
      setExportJob(await requestExport());
    } catch (err) {
      setExportError(errorMessage(err));
    } finally {
      setExportBusy(false);
    }
  };

  const handleLogoutAll = async () => {
    if (!window.confirm('Sign out on every device?')) return;
    setAccountError(null);
    try {
      await logoutAll();
      location.href = '/';
    } catch (err) {
      setAccountError(errorMessage(err));
    }
  };

  const handleDeleteAccount = async () => {
    if (!window.confirm('Delete your account? All channels you own, clips and files will be removed.'))
      return;
    if (!window.confirm('This is permanent. Really delete your account?')) return;
    setAccountError(null);
    try {
      await deleteAccount();
      location.reload();
    } catch (err) {
      setAccountError(errorMessage(err));
    }
  };

  return (
    <div
      className="modal-backdrop"
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div className="modal card" role="dialog" aria-modal="true" aria-label="Account settings">
        <header className="panel-header">
          <h2>Account settings</h2>
          <button
            ref={closeRef}
            type="button"
            className="icon-btn"
            aria-label="Close settings"
            onClick={onClose}
          >
            <CloseIcon size={16} />
          </button>
        </header>
        <p className="muted">{user.email}</p>

        <section aria-label="Device">
          <h3>Device label</h3>
          <p className="muted">Sent as the origin of everything you push from this device.</p>
          <div className="form-row">
            <input
              type="text"
              value={device}
              aria-label="Device label"
              onChange={(event) => {
                setDevice(event.target.value);
                setDeviceSaved(false);
              }}
            />
            <button type="button" className="btn" disabled={!device.trim()} onClick={saveDevice}>
              Save
            </button>
            {deviceSaved && <span className="muted">Saved</span>}
          </div>
        </section>

        {config.pushEnabled && (
          <section aria-label="Notifications">
            <h3>Notifications</h3>
            <p className="muted">Get a notification when a new item arrives in your channels.</p>
            {pushError && <p className="error">{pushError}</p>}
            <div className="form-row">
              {pushState === 'unsupported' ? (
                <span className="muted">Push notifications are not supported by this browser.</span>
              ) : pushState === 'blocked' ? (
                <span className="muted">
                  Notifications are blocked for this site — allow them in your browser settings
                  first.
                </span>
              ) : pushState === 'checking' ? (
                <span className="muted">Checking notification state…</span>
              ) : pushState === 'enabled' ? (
                <>
                  <span className="badge">enabled</span>
                  <button
                    type="button"
                    className="btn"
                    disabled={pushBusy}
                    onClick={() => void handleDisablePush()}
                  >
                    {pushBusy ? 'Disabling…' : 'Disable'}
                  </button>
                </>
              ) : (
                <button
                  type="button"
                  className="btn"
                  disabled={pushBusy || !config.vapidPublicKey}
                  onClick={() => void handleEnablePush()}
                >
                  {pushBusy ? 'Enabling…' : 'Enable notifications'}
                </button>
              )}
            </div>
          </section>
        )}

        <section aria-label="API tokens">
          <h3>API tokens</h3>
          {tokenError && <p className="error">{tokenError}</p>}
          {createdToken && (
            <div className="token-secret">
              <p>
                Copy this token now — it will not be shown again
                (prefix <code>{createdToken.prefix}</code>):
              </p>
              <div className="form-row">
                <code className="secret">{createdToken.token}</code>
                <button
                  type="button"
                  className="btn small"
                  onClick={() => {
                    void copyText(createdToken.token).then(setSecretCopied);
                  }}
                >
                  {secretCopied ? 'Copied' : 'Copy'}
                </button>
                <button
                  type="button"
                  className="btn small"
                  onClick={() => setCreatedToken(null)}
                >
                  Done
                </button>
              </div>
            </div>
          )}
          {tokens === null ? (
            <p className="muted">Loading tokens…</p>
          ) : tokens.length === 0 ? (
            <p className="muted">No tokens yet.</p>
          ) : (
            <ul className="token-list">
              {tokens.map((token) => (
                <li key={token.id} className="token-row">
                  <div className="member-info">
                    <span>
                      {token.name} <code>{token.prefix}…</code>
                    </span>
                    <span className="muted">
                      created {relativeTime(token.createdAt, now)} · last used{' '}
                      {token.lastUsedAt ? relativeTime(token.lastUsedAt, now) : 'never'}
                    </span>
                  </div>
                  <span className="badge">{token.scope}</span>
                  {token.revokedAt ? (
                    <span className="badge pending">revoked</span>
                  ) : (
                    <button
                      type="button"
                      className="btn danger-ghost small"
                      onClick={() => void handleRevoke(token)}
                    >
                      Revoke
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
          <form className="form-row" onSubmit={(event) => void handleCreateToken(event)}>
            <input
              type="text"
              value={tokenName}
              placeholder="Token name (e.g. ios-shortcut)"
              aria-label="Token name"
              onChange={(event) => setTokenName(event.target.value)}
            />
            <select
              value={tokenScope}
              aria-label="Token scope"
              onChange={(event) => setTokenScope(event.target.value === 'read' ? 'read' : 'write')}
            >
              <option value="read">read</option>
              <option value="write">write</option>
            </select>
            <button type="submit" className="btn" disabled={tokenBusy || !tokenName.trim()}>
              {tokenBusy ? 'Creating…' : 'Create token'}
            </button>
          </form>
        </section>

        <section aria-label="Data and sessions">
          <h3>Data & sessions</h3>
          {exportError && <p className="error">{exportError}</p>}
          <div className="form-row">
            <button
              type="button"
              className="btn"
              disabled={exportBusy || exportJob?.status === 'pending' || exportJob?.status === 'running'}
              onClick={() => void handleExport()}
            >
              Export my data
            </button>
            {exportJob &&
              (exportJob.status === 'done' ? (
                <a className="btn primary" href={exportDownloadUrl(exportJob.jobId)} download>
                  Download export
                </a>
              ) : (
                <span className="muted">
                  {exportJob.status === 'failed' ? 'Export failed.' : `Export ${exportJob.status}…`}
                </span>
              ))}
          </div>
          <div className="form-row">
            <button type="button" className="btn" onClick={() => void handleLogoutAll()}>
              Sign out everywhere
            </button>
          </div>
        </section>

        <section aria-label="Danger zone">
          <h3>Danger zone</h3>
          {accountError && <p className="error">{accountError}</p>}
          <button type="button" className="btn danger" onClick={() => void handleDeleteAccount()}>
            Delete account
          </button>
        </section>
      </div>
    </div>
  );
}
