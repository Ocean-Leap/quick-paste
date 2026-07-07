import { useCallback, useEffect, useState } from 'react';
import { ApiError, errorMessage, getConfig, getMe } from './api/client';
import type { ClientConfigDto, MeResponse } from './api/types';
import { Shell } from './components/Shell';
import { SignIn } from './components/SignIn';
import { AppStoreProvider } from './state/store';

type AuthState =
  | { phase: 'loading' }
  | { phase: 'signed-out' }
  | { phase: 'error'; message: string }
  | { phase: 'ready'; me: MeResponse; config: ClientConfigDto };

export function App() {
  const [auth, setAuth] = useState<AuthState>({ phase: 'loading' });

  const bootstrap = useCallback(async () => {
    setAuth({ phase: 'loading' });
    try {
      const me = await getMe();
      const config = await getConfig();
      setAuth({ phase: 'ready', me, config });
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setAuth({ phase: 'signed-out' });
      } else {
        setAuth({ phase: 'error', message: errorMessage(err) });
      }
    }
  }, []);

  useEffect(() => {
    void bootstrap();
  }, [bootstrap]);

  switch (auth.phase) {
    case 'loading':
      return (
        <div className="boot" aria-busy="true" aria-label="Loading">
          <div className="skeleton skeleton-line" />
          <div className="skeleton skeleton-block" />
          <div className="skeleton skeleton-line short" />
        </div>
      );
    case 'signed-out':
      return <SignIn />;
    case 'error':
      return (
        <div className="boot">
          <p className="error" role="alert">
            {auth.message}
          </p>
          <button type="button" className="btn" onClick={() => void bootstrap()}>
            Retry
          </button>
        </div>
      );
    case 'ready':
      return (
        <AppStoreProvider me={auth.me} config={auth.config}>
          <Shell />
        </AppStoreProvider>
      );
  }
}
