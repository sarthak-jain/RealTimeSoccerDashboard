import { createContext, useState, useEffect, useCallback } from 'react';
import api from '../services/api';
import { datadogRum } from '@datadog/browser-rum';

export const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => {
    const saved = localStorage.getItem('user');
    return saved ? JSON.parse(saved) : null;
  });
  const [token, setToken] = useState(() => localStorage.getItem('token'));

  useEffect(() => {
    const handleExpired = () => {
      setUser(null);
      setToken(null);
    };
    window.addEventListener('auth-expired', handleExpired);
    return () => window.removeEventListener('auth-expired', handleExpired);
  }, []);

  const login = useCallback(async (email, password) => {
    const { data } = await api.post('/auth/login', { email, password });
    localStorage.setItem('token', data.token);
    localStorage.setItem('user', JSON.stringify({ username: data.username, email: data.email }));
    setToken(data.token);
    setUser({ username: data.username, email: data.email });
    datadogRum.setUser({ id: data.email, name: data.username, email: data.email });
    return data;
  }, []);

  const signup = useCallback(async (username, email, password) => {
    const { data } = await api.post('/auth/signup', { username, email, password });
    localStorage.setItem('token', data.token);
    localStorage.setItem('user', JSON.stringify({ username: data.username, email: data.email }));
    setToken(data.token);
    setUser({ username: data.username, email: data.email });
    datadogRum.setUser({ id: data.email, name: data.username, email: data.email });
    return data;
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem('token');
    localStorage.removeItem('user');
    setToken(null);
    setUser(null);
    datadogRum.clearUser();
  }, []);

  return (
    <AuthContext.Provider value={{ user, token, login, signup, logout, isAuthenticated: !!token }}>
      {children}
    </AuthContext.Provider>
  );
}
