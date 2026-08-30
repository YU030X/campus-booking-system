import axios from 'axios';
import { dispatchMock } from './authMock';

export const mode = import.meta.env.VITE_API_MODE || 'real';
if (!['mock', 'real'].includes(mode)) throw new Error(`Invalid VITE_API_MODE: ${mode}`);
export const http = axios.create({ baseURL: mode === 'real' ? '/api/v1' : '', adapter: mode === 'mock' ? dispatchMock : undefined });
http.interceptors.request.use((config) => { try { const session = JSON.parse(sessionStorage.getItem('campus.auth.session') || 'null'); const valid = session && typeof session.token === 'string' && session.token.length > 0 && session.tokenType === 'Bearer' && Number.isFinite(session.expiresAt) && session.expiresAt > Date.now(); if (valid) { const value = `${session.tokenType} ${session.token}`; config.headers = config.headers || {}; if (typeof config.headers.set === 'function') config.headers.set('Authorization', value); else config.headers.Authorization = value; } } catch { /* malformed storage */ } return config; });
const authPath = (url = '') => { try { return new URL(url, 'http://local').pathname.replace(/^\/api\/v1/, ''); } catch { return String(url); } };
let auth401Promise = null;
export function installAuthHandlers({ on401, on403 } = {}) { return http.interceptors.response.use((response) => response, (error) => { const status = error.response?.status; const path = authPath(error.config?.url); if (status === 401 && !/^\/auth\/(?:login|register)$/.test(path)) { if (!auth401Promise) auth401Promise = Promise.resolve(on401?.(error)).finally(() => { auth401Promise = null; }); error.authRecovery = auth401Promise; } if (status === 403) on403?.(error); return Promise.reject(error); }); }
export const getAuth401Promise = () => auth401Promise;
