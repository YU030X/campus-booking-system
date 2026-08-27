import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizeUsersQuery, requestQueryKey, MAX_PAGE_SIZE } from '../../src/api/adminUsers.js';

test('defaults produce page 1 size 10 without empty filters', () => {
  assert.deepEqual(normalizeUsersQuery({}), { pageNumber: 1, pageSize: 10 });
});

test('pageNumber clamps to >=1 and accepts numeric strings', () => {
  const query = normalizeUsersQuery({ pageNumber: '3', pageSize: '20' });
  assert.equal(query.pageNumber, 3);
  assert.equal(query.pageSize, 20);
  assert.deepEqual(normalizeUsersQuery({ pageNumber: -5 }).pageNumber, 1);
  assert.deepEqual(normalizeUsersQuery({ pageNumber: 'abc' }).pageNumber, 1);
  assert.deepEqual(normalizeUsersQuery({ pageNumber: 2.5 }).pageNumber, 1);
});

test('pageSize clamps to MAX_PAGE_SIZE and falls back on invalid values', () => {
  assert.equal(normalizeUsersQuery({ pageSize: 250 }).pageSize, MAX_PAGE_SIZE);
  assert.equal(normalizeUsersQuery({ pageSize: 0 }).pageSize, 10);
  assert.equal(normalizeUsersQuery({ pageSize: null }).pageSize, 10);
});

test('keyword is trimmed and empty keyword omitted', () => {
  const withKeyword = normalizeUsersQuery({ keyword: '  张三  ' });
  assert.equal(withKeyword.keyword, '张三');
  const noKeyword = normalizeUsersQuery({ keyword: '   ' });
  assert.ok(!('keyword' in noKeyword));
  assert.deepEqual(normalizeUsersQuery({ keyword: 42 }), { pageNumber: 1, pageSize: 10 });
});

test('role allow-list drops unknown roles silently', () => {
  assert.equal(normalizeUsersQuery({ role: 'STUDENT' }).role, 'STUDENT');
  assert.equal(normalizeUsersQuery({ role: 'ADMIN' }).role, 'ADMIN');
  assert.ok(!('role' in normalizeUsersQuery({ role: 'ROOT' })));
  assert.ok(!('role' in normalizeUsersQuery({ role: '' })));
  assert.ok(!('role' in normalizeUsersQuery({ role: 'student' })));
});

test('status accepts only numeric 0|1 (or exact strings) and omits unknowns', () => {
  assert.equal(normalizeUsersQuery({ status: 0 }).status, 0);
  assert.equal(normalizeUsersQuery({ status: 1 }).status, 1);
  assert.equal(normalizeUsersQuery({ status: '0' }).status, 0);
  assert.equal(normalizeUsersQuery({ status: '1' }).status, 1);
  for (const bad of ['2', 'on', -1, true, {}, null]) {
    const params = normalizeUsersQuery({ status: bad });
    assert.ok(!('status' in params), `expected status dropped for ${String(bad)}`);
  }
});

test('combined query keeps canonical key shape and stable serialization', () => {
  const params = normalizeUsersQuery({
    pageNumber: 2,
    pageSize: 50,
    keyword: ' 李四 ',
    role: 'ADMIN',
    status: '0',
  });
  assert.deepEqual(params, { pageNumber: 2, pageSize: 50, keyword: '李四', role: 'ADMIN', status: 0 });
  assert.equal(requestQueryKey(params), `list:${JSON.stringify(params)}`);
  assert.equal(
    requestQueryKey(normalizeUsersQuery({ role: 'ADMIN', status: '0', keyword: '李四', pageNumber: 2, pageSize: 50 })),
    requestQueryKey(params),
    'input order must not affect dedup key',
  );
});
