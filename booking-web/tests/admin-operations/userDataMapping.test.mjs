import test from 'node:test';
import assert from 'node:assert/strict';
import { validateUserView, mapUserPage, USER_VIEW_FIELDS } from '../../src/api/adminUsers.js';

const fullUser = () => ({
  id: '42',
  username: 'alice',
  realName: '张三',
  studentNo: '20240001',
  phone: '13800000000',
  email: 'a@example.com',
  avatar: '',
  role: 'STUDENT',
  creditScore: 100,
  status: 1,
  createdAt: '2026-08-27 10:00:00',
  updatedAt: '2026-08-27 10:00:00',
});

test('UserView contract freezes exactly 12 fields', () => {
  assert.deepEqual(USER_VIEW_FIELDS.sort(), [
    'avatar', 'createdAt', 'creditScore', 'email', 'id', 'phone', 'realName',
    'role', 'status', 'studentNo', 'updatedAt', 'username',
  ]);
});

test('validateUserView passes a conforming record and preserves server truth verbatim', () => {
  const record = validateUserView(fullUser());
  assert.equal(record.id, '42');
  assert.equal(record.status, 1);
});

test('numeric ids are rejected as contract drift', () => {
  const user = fullUser();
  user.id = 42;
  assert.throws(() => validateUserView(user), /十进制字符串/);
});

test('unknown or missing fields are rejected', () => {
  const extra = fullUser();
  extra.passwordHash = 'x';
  assert.throws(() => validateUserView(extra), /未知字段/);
  const missing = fullUser();
  delete missing.avatar;
  assert.throws(() => validateUserView(missing), /缺少字段/);
});

test('role/status vocabularies are enforced', () => {
  const badRole = fullUser();
  badRole.role = 'ROOT';
  assert.throws(() => validateUserView(badRole));
  const badStatus = fullUser();
  badStatus.status = 'enabled';
  assert.throws(() => validateUserView(badStatus));
});

test('timestamps must be yyyy-MM-dd HH:mm:ss strings', () => {
  const badCreated = fullUser();
  badCreated.createdAt = '2026-08-27T10:00:00';
  assert.throws(() => validateUserView(badCreated), /createdAt/);
  const shortUpdated = fullUser();
  shortUpdated.updatedAt = '2026/08/27 10:00:00';
  assert.throws(() => validateUserView(shortUpdated), /updatedAt/);
});

test('creditScore must be an integer', () => {
  const fractional = fullUser();
  fractional.creditScore = 99.5;
  assert.throws(() => validateUserView(fractional), /creditScore/);
  const textual = fullUser();
  textual.creditScore = '100';
  assert.throws(() => validateUserView(textual), /creditScore/);
});

test('mapUserPage canonicalizes PageResult and maps every record', () => {
  const page = mapUserPage({
    records: [fullUser()],
    pageNumber: 2,
    pageSize: 20,
    total: '35',
  });
  assert.equal(page.records.length, 1);
  assert.deepEqual(page, {
    records: [fullUser()],
    pageNumber: 2,
    pageSize: 20,
    total: 35,
  });
});

test('malformed PageResult payloads are rejected instead of faked as empty', () => {
  assert.throws(() => mapUserPage(null), /PageResult/);
  assert.throws(() => mapUserPage('oops'), /PageResult/);
  assert.throws(() => mapUserPage([1, 2]), /PageResult|records/);
  assert.throws(() => mapUserPage({}), /records/);
  assert.throws(() => mapUserPage({ records: 'nope', pageNumber: 1, pageSize: 10, total: 0 }), /records/);
  assert.throws(() => mapUserPage({ records: [], pageNumber: 0, pageSize: 10, total: 0 }), /pageNumber/);
  assert.throws(() => mapUserPage({ records: [], pageNumber: 1, pageSize: 101, total: 0 }), /pageSize/);
  assert.throws(() => mapUserPage({ records: [], pageNumber: 'x', pageSize: 10, total: 0 }), /pageNumber/);
  assert.throws(() => mapUserPage({ records: [] }), /pageNumber|total/);
});
