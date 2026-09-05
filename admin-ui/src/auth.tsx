import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, setUnauthorizedHandler, type Me } from './api';

interface Auth {
  me: Me | null;
  ready: boolean;
  login(username: string, password: string): Promise<void>;
  logout(): Promise<void>;
}

const Ctx = createContext<Auth>(null!);

// The session is the cookie; on load the UI asks the service who it is rather than trusting
// anything stored in the browser. A 401 from any call afterwards drops back to sign-in.
export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [ready, setReady] = useState(false);
  useEffect(() => {
    setUnauthorizedHandler(() => setMe(null));
    api.csrf().catch(() => undefined)
      .then(() => api.me().then(setMe, () => setMe(null)))
      .finally(() => setReady(true));
  }, []);
  const login = useCallback(async (u: string, p: string) => { await api.csrf().catch(() => undefined); setMe(await api.login(u, p)); }, []);
  const logout = useCallback(async () => { try { await api.logout(); } finally { setMe(null); await api.csrf().catch(() => undefined); } }, []);
  const value = useMemo(() => ({ me, ready, login, logout }), [me, ready, login, logout]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export const useAuth = () => useContext(Ctx);
