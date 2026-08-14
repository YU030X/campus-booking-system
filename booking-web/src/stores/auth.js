import { defineStore } from 'pinia';
import * as api from '../api/auth';
const KEY = 'campus.auth.session';
export const safeRedirect = (value) => {
  if (typeof value !== 'string') return '/resources';
  let current = value;
  try {
    for (let round = 0; round < 3; round += 1) {
      const decoded = decodeURIComponent(current);
      if (decoded === current) break;
      current = decoded;
    }
    if (/%[0-9a-f]{2}/i.test(current) || decodeURIComponent(current) !== current) return '/resources';
  } catch { return '/resources'; }
  if (!current.startsWith('/') || current.startsWith('//') || current.includes('\\') || /[\u0000-\u001f\u007f]/.test(current) || /^[a-z][a-z0-9+.-]*:/i.test(current)) return '/resources';
  return current;
};
export const useAuthStore = defineStore('auth', { state: () => ({ user: null, session: null, hydrated: false, hydrating: null, forbidden: false }), getters: { isAuthenticated: (s) => !!s.session && s.session.expiresAt > Date.now() + Math.min(30000, Math.max(0, Math.floor((s.session.expiresAt - Date.now()) / 10))), role: (s) => s.user?.role }, actions: {
  async ensureHydrated() { if (this.hydrated) return; if (this.hydrating) return this.hydrating; this.hydrating = (async () => { try { const parsed = JSON.parse(sessionStorage.getItem(KEY) || 'null'); const remaining = Number(parsed?.expiresAt) - Date.now(); const skew = Math.min(30000, Math.max(0, Math.floor(remaining / 10))); if (!parsed || typeof parsed.token !== 'string' || parsed.tokenType !== 'Bearer' || !Number.isFinite(parsed.expiresAt) || parsed.expiresAt <= Date.now() + skew) throw new Error('expired'); this.session = { token: parsed.token, tokenType: parsed.tokenType, expiresAt: parsed.expiresAt }; this.user = (await api.me()).data.data; } catch { this.clear(); } finally { this.hydrated = true; this.hydrating = null; } })(); return this.hydrating; },
  async login(payload) {
    try {
      const data = (await api.login(payload)).data.data;
      const validUser = data?.user && typeof data.user === 'object' && !Array.isArray(data.user) && ['STUDENT', 'ADMIN'].includes(data.user.role) && [0, 1].includes(data.user.status);
      if (!data || typeof data.token !== 'string' || !data.token || data.tokenType !== 'Bearer' || !Number.isInteger(data.expiresIn) || data.expiresIn < 1 || data.expiresIn > 86400 || !validUser) throw new Error('invalid login response');
      const session = { token: data.token, tokenType: data.tokenType, expiresAt: Date.now() + data.expiresIn * 1000 };
      this.session = session;
      this.user = data.user;
      sessionStorage.setItem(KEY, JSON.stringify(session));
      return data;
    } catch (error) {
      this.clear();
      throw error;
    }
  },
  async register(payload) { const { confirmPassword, ...request } = payload; return api.register(request); },
  clear() { this.user = null; this.session = null; this.forbidden = false; sessionStorage.removeItem(KEY); }, logout() { this.clear(); }, setForbidden(value = true) { this.forbidden = value; },
} });
