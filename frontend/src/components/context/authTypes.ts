import { createContext } from "react";

export interface AuthContextType {
  isAuthenticated: boolean;
  username: string | null;
  loading: boolean;
  loginSuccess: (username: string) => void;
  logoutSuccess: () => void;
}

export const AuthContext = createContext<AuthContextType | undefined>(undefined);
