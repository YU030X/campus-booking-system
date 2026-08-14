const USER_KEYS = ['id', 'username', 'realName', 'studentNo', 'phone', 'email', 'avatar', 'role', 'creditScore', 'status', 'createdAt', 'updatedAt'];
const toUserView = (value) => Object.fromEntries(USER_KEYS.map((key) => [key, value?.[key] === undefined ? null : value[key]]));

const accounts = new Map();
const tokens = new Map();
let idSequence = 0;
let tokenSequence = 0;
const makeAccount = (id, username, realName, role = 'STUDENT', password = '') => ({ id: String(id), username, realName, studentNo: null, phone: null, email: null, avatar: null, role, creditScore: 100, status: 1, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', password });
accounts.set('student_demo', makeAccount(++idSequence, 'student_demo', 'Demo Student', 'STUDENT', 'student123'));
accounts.set('admin_demo', makeAccount(++idSequence, 'admin_demo', 'Demo Admin', 'ADMIN', 'admin123'));

const envelope = (config, status, data) => Promise.resolve({ status, statusText: '', data: { code: 0, message: 'success', data }, headers: {}, config, request: null });
const fail = (config, status, code, message) => { const error = new Error(message); error.isAxiosError = true; error.config = config; error.response = { status, data: { code, message, data: null }, headers: {}, config }; error.status = status; return Promise.reject(error); };
const safeUser = (account) => toUserView(account);
const textBytes = (value) => new TextEncoder().encode(value).length;
const normalizeOptional = (value) => value.trim() || null;
const validPhone = (value) => value === null || /^1[3-9]\d{9}$/.test(value);
const validEmail = (value) => value === null || (value.length <= 100 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value));
const encodeTokenPart = (value) => Array.from(value).map((character) => character.codePointAt(0).toString(16)).join('.');
const decodeTokenPart = (value) => {
  try {
    if (typeof value !== 'string' || !value) return null;
    const parts = value.split('.');
    if (parts.some((part) => !part || !/^[0-9a-f]+$/i.test(part))) return null;
    const codePoints = parts.map((part) => Number.parseInt(part, 16));
    if (codePoints.some((codePoint) => !Number.isInteger(codePoint) || codePoint < 0 || codePoint > 0x10ffff || (codePoint >= 0xd800 && codePoint <= 0xdfff))) return null;
    return String.fromCodePoint(...codePoints);
  } catch { return null; }
};
const issueToken = (account) => {
  const expiresAt = Date.now() + 7200000;
  const token = `mock-token-${encodeTokenPart(account.username)}-${expiresAt}`;
  tokens.set(token, { account, expiresAt });
  return { token, expiresAt };
};
const resolveToken = (token) => {
  const existing = tokens.get(token);
  if (existing) return existing;
  const match = /^mock-token-(.+)-(\d+)$/.exec(token);
  if (!match) return null;
  const decoded = decodeTokenPart(match[1]);
  if (decoded === null) return null;
  const account = accounts.get(decoded);
  if (!account) return null;
  const record = { account, expiresAt: Number(match[2]) };
  tokens.set(token, record);
  return record;
};

export async function dispatchMock(config) {
  const method = String(config.method || 'get').toLowerCase();
  const path = new URL(config.url || '', 'http://mock.local').pathname;
  let body = config.data;
  if (typeof body === 'string') { try { body = JSON.parse(body); } catch { body = {}; } }
  if (method === 'post' && path === '/auth/register') {
    const keys = ['username', 'password', 'realName', 'studentNo', 'phone', 'email'];
    if (!body || typeof body !== 'object' || Object.keys(body).some((key) => !keys.includes(key)) || typeof body.username !== 'string' || typeof body.password !== 'string' || typeof body.realName !== 'string') return fail(config, 400, 40000, 'invalid parameter');
    const username = body.username.trim(); const realName = body.realName.trim();
    const studentNo = body.studentNo === undefined || body.studentNo === null ? null : typeof body.studentNo === 'string' ? normalizeOptional(body.studentNo) : '__invalid__';
    const phone = body.phone === undefined || body.phone === null ? null : typeof body.phone === 'string' ? normalizeOptional(body.phone) : '__invalid__';
    const email = body.email === undefined || body.email === null ? null : typeof body.email === 'string' ? normalizeOptional(body.email) : '__invalid__';
    if (!/^[A-Za-z0-9_]{3,50}$/.test(username) || textBytes(body.password) < 8 || textBytes(body.password) > 72 || realName.length < 1 || realName.length > 50 || (studentNo !== null && (studentNo === '__invalid__' || studentNo.length > 30)) || !validPhone(phone) || !validEmail(email)) return fail(config, 400, 40000, 'invalid parameter');
    if (accounts.has(username)) return fail(config, 409, 41000, 'username already exists');
    const account = { ...makeAccount(++idSequence, username, realName, 'STUDENT', body.password), studentNo, phone, email };
    accounts.set(account.username, account); return envelope(config, 201, safeUser(account));
  }
  if (method === 'post' && path === '/auth/login') {
    if (!body || typeof body !== 'object' || Object.keys(body).some((key) => !['username', 'password'].includes(key)) || typeof body.username !== 'string' || typeof body.password !== 'string' || !/^[A-Za-z0-9_]{3,50}$/.test(body.username.trim()) || textBytes(body.password) < 8 || textBytes(body.password) > 72) return fail(config, 400, 40000, 'invalid parameter');
    const account = accounts.get(body.username.trim());
    if (!account || account.password !== body?.password || account.status !== 1) return fail(config, 401, 40100, '账号或密码错误');
    const issued = issueToken(account); tokenSequence += 1;
    return envelope(config, 200, { token: issued.token, tokenType: 'Bearer', expiresIn: 7200, user: safeUser(account) });
  }
  if ((method === 'get' || method === 'put') && path === '/users/me') {
    const rawAuthorization = typeof config.headers?.get === 'function' ? config.headers.get('Authorization') : config.headers?.Authorization;
    const token = String(rawAuthorization || '').replace(/^Bearer\s+/i, ''); const record = resolveToken(token);
    if (config.mockExpired || !record || record.expiresAt <= Date.now()) return fail(config, 401, 40100, 'unauthenticated');
    if (config.mockForbidden) return fail(config, 403, 40300, 'forbidden');
    if (method === 'get' && body !== undefined && body !== null && body !== '') return fail(config, 400, 40000, 'invalid parameter');
    if (method === 'put') {
      const allowed = ['realName', 'phone', 'email', 'avatar'];
      if (!body || typeof body !== 'object' || Object.keys(body).some((key) => !allowed.includes(key)) || typeof body.realName !== 'string') return fail(config, 400, 40000, 'invalid parameter');
      const realName = body.realName.trim(); const phone = body.phone === undefined || body.phone === null ? null : typeof body.phone === 'string' ? normalizeOptional(body.phone) : '__invalid__'; const email = body.email === undefined || body.email === null ? null : typeof body.email === 'string' ? normalizeOptional(body.email) : '__invalid__'; const avatar = body.avatar === undefined || body.avatar === null ? null : typeof body.avatar === 'string' ? normalizeOptional(body.avatar) : '__invalid__';
      if (realName.length < 1 || realName.length > 50 || !validPhone(phone) || !validEmail(email) || (avatar !== null && (avatar === '__invalid__' || typeof avatar !== 'string' || avatar.length > 255))) return fail(config, 400, 40000, 'invalid parameter');
      Object.assign(record.account, { realName, phone, email, avatar });
    }
    return envelope(config, 200, safeUser(record.account));
  }
  return fail(config, 404, 40400, 'mock endpoint not implemented');
}
export const __mock = { accounts, tokens };
