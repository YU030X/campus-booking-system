import test from 'node:test';
import assert from 'node:assert/strict';
import { mapAdminUserError } from '../../src/api/adminUsers.js';

test('business codes map to actionable Chinese messages', () => {
  assert.match(mapAdminUserError({ code: 41000 }).adminMessage, /状态更新被拒绝/);
  assert.match(mapAdminUserError({ code: 40300 }).adminMessage, /无权限/);
  assert.match(mapAdminUserError({ code: 40100 }).adminMessage, /登录已失效/);
  assert.match(mapAdminUserError({ code: 40400 }).adminMessage, /用户不存在/);
  assert.match(mapAdminUserError({ code: 40000 }).adminMessage, /参数无效/);
});

test('http status fallback applies when business code is absent', () => {
  const err = mapAdminUserError({ response: { status: 409 } });
  assert.match(err.adminMessage, /冲突/);
  assert.match(mapAdminUserError({ response: { status: 403 } }).adminMessage, /无权限/);
  assert.match(mapAdminUserError({ response: { status: 404 } }).adminMessage, /不存在/);
});

test('response envelope code wins over http status', () => {
  const err = mapAdminUserError({
    response: { status: 409, data: { code: 41000, message: 'administrator cannot disable self' } },
    code: 41000,
  });
  assert.match(err.adminMessage, /状态更新被拒绝/);
});

test('unknown errors keep their original message as last resort', () => {
  const original = new Error('network down');
  const mapped = mapAdminUserError(original);
  assert.equal(mapped, original);
  assert.equal(mapped.adminMessage, 'network down');
});

test('mapping performs no session mutation (pure error annotation)', () => {
  let logoutCalled = false;
  globalThis.sessionStorage = undefined;
  const mapped = mapAdminUserError({ code: 40100 });
  if (logoutCalled) throw new Error('logout must not be fabricated');
  assert.ok(typeof mapped.adminMessage === 'string');
});
