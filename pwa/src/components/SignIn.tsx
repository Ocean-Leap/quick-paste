import { useCallback, useEffect, useState } from 'react';
import { errorMessage, getAuthMode, loginLocal } from '../api/client';

/**
 * Sign-in adapts to the instance's auth mode: Google OIDC (public FQDN
 * deployments) or a local password (LAN deployments without a domain).
 */
export function SignIn() {
  const loginError = new URLSearchParams(location.search).get('login_error');
  const [mode, setMode] = useState<'google' | 'local' | 'error' | null>(null);
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const discover = useCallback(() => {
    let cancelled = false;
    setMode(null);
    getAuthMode()
      .then((m) => {
        if (!cancelled) setMode(m.mode === 'local' ? 'local' : 'google');
      })
      .catch(() => {
        // Defaulting to the Google button here would dead-end local-mode LAN
        // boxes (no OIDC) — surface a retry instead.
        if (!cancelled) setMode('error');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => discover(), [discover]);

  const submitPassword = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!password || busy) return;
    setBusy(true);
    setError(null);
    try {
      await loginLocal(password);
      location.replace('/');
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  };

  return (
    <div className="signin">
      <div className="signin-card card">
        <h1 className="wordmark">quick-paste</h1>
        <p className="muted">One clipboard for every device — paste here, it appears everywhere.</p>
        {loginError && (
          <p className="signin-error" role="alert">
            {loginError === 'not_authorized'
              ? 'This Google account is not on the list yet. Ask the person running this quick-paste to invite you, then sign in again.'
              : 'Sign-in failed. Please try again.'}
          </p>
        )}
        {mode === 'error' ? (
          <div className="signin-form">
            <p className="signin-error" role="alert">
              Couldn't reach the server.
            </p>
            <button type="button" className="btn primary" onClick={() => discover()}>
              Retry
            </button>
          </div>
        ) : mode === 'local' ? (
          <form className="signin-form" onSubmit={(e) => void submitPassword(e)}>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="Password"
              aria-label="Password"
              autoComplete="current-password"
              autoFocus
            />
            {error && (
              <p className="signin-error" role="alert">
                {error}
              </p>
            )}
            <button type="submit" className="btn primary" disabled={!password || busy}>
              {busy ? 'Signing in…' : 'Sign in'}
            </button>
          </form>
        ) : (
          <button
            type="button"
            className="btn primary"
            disabled={mode === null}
            onClick={() => {
              location.href = '/api/auth/login';
            }}
          >
            Sign in with Google
          </button>
        )}
      </div>
    </div>
  );
}
