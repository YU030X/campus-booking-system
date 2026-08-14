import { http } from './http';

const USER_KEYS = ['id', 'username', 'realName', 'studentNo', 'phone', 'email', 'avatar', 'role', 'creditScore', 'status', 'createdAt', 'updatedAt'];

export function toUserView(value) {
  const source = value && typeof value === 'object' ? value : {};
  return Object.fromEntries(USER_KEYS.map((key) => [key, source[key] === undefined ? null : source[key]]));
}

export const register = (payload) => http.post('/auth/register', payload).then((response) => ({ ...response, data: { ...response.data, data: toUserView(response.data.data) } }));
export const login = (payload) => http.post('/auth/login', payload).then((response) => ({ ...response, data: { ...response.data, data: { ...response.data.data, user: toUserView(response.data.data.user) } } }));
export const me = () => http.get('/users/me').then((response) => ({ ...response, data: { ...response.data, data: toUserView(response.data.data) } }));
export const updateMe = (payload) => {
  const source = payload && typeof payload === 'object' ? payload : {};
  const request = Object.fromEntries(['realName', 'phone', 'email', 'avatar'].filter((key) => Object.prototype.hasOwnProperty.call(source, key)).map((key) => [key, source[key]]));
  return http.put('/users/me', request).then((response) => ({ ...response, data: { ...response.data, data: toUserView(response.data.data) } }));
};
