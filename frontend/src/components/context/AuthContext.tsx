import {
  useState,
  useEffect,
  useCallback,
  useMemo,
  type ReactNode,
} from "react";
import { getAuthenticated } from "../../api";
import { AuthContext } from "./authTypes";

export function AuthProvider({ children }: { children: ReactNode }) {
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [username, setUsername] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getAuthenticated()
      .then((res) => {
        setIsAuthenticated(true);
        setUsername(res.username);
      })
      .catch(() => {
        // Not authenticated — leave defaults
      })
      .finally(() => setLoading(false));
  }, []);

  const loginSuccess = useCallback((name: string) => {
    setIsAuthenticated(true);
    setUsername(name);
  }, []);

  const logoutSuccess = useCallback(() => {
    setIsAuthenticated(false);
    setUsername(null);
  }, []);

  const value = useMemo(
    () => ({ isAuthenticated, username, loading, loginSuccess, logoutSuccess }),
    [isAuthenticated, username, loading, loginSuccess, logoutSuccess],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
