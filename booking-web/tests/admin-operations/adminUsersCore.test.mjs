import test from 'node:test';
import assert from 'node:assert/strict';
import { createAdminUsersCore } from '../../src/api/adminUsers.js';

const user = (id, overrides = {}) => ({
  id,
  username: `user${id}`,
  realName: '',
  studentNo: '',
  phone: '',
  email: '',
  avatar: '',
  role: 'STUDENT',
  creditScore: 100,
  status: 1,
  createdAt: '2026-08-27 10:00:00',
  updatedAt: '2026-08-27 10:00:00',
  ...overrides,
});

const deferred = () => {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
};

function fakeTransport() {
  const calls = { list: [], updateStatus: [] };
  const pendingList = [];
  const pendingStatus = [];
  return {
    calls,
    pendingList,
    pendingStatus,
    list(params) {
      calls.list.push(params);
      const d = deferred();
      pendingList.push(d);
      return d.promise;
    },
    updateStatus(id, status) {
      calls.updateStatus.push({ id, body: { status } });
      const d = deferred();
      pendingStatus.push(d);
      return d.promise;
    },
  };
}

async function seed(core, transport, rows) {
  const done = core.fetchList();
  transport.pendingList[0].resolve({ records: rows, pageNumber: 1, pageSize: 10, total: rows.length });
  await done;
}

test('identical concurrent fetches de-duplicate into one request without stale-dropping the shared result', async () => {
  const transport = fakeTransport();
  const core = createAdminUsersCore(transport);
  const first = core.fetchList();
  const second = core.fetchList();
  assert.strictEqual(first, second);
  transport.pendingList[0].resolve({ records: [user('7')], pageNumber: 1, pageSize: 10, total: 1 });
  const [resultA, resultB] = await Promise.all([first, second]);
  assert.equal(resultA, resultB);
  assert.equal(transport.calls.list.length, 1);
  assert.equal(core.state.page.phase, 'success', 'shared request result must still be applied');
  assert.equal(core.state.page.records[0].id, '7');
});

test('failed truth refresh after successful PATCH keeps op success and only fails page state', async () => {
  const transport = fakeTransport();
  const core = createAdminUsersCore(transport);
  await seed(core, transport, [user('21')]);

  const op = core.changeStatus('21', 0).then((value) => ({ ok: true, value }), (error) => ({ ok: false, error }));
  transport.pendingStatus[0].resolve(user('21', { status: 0 }));
  transport.pendingList[1].reject({ response: { status: 500 }, message: 'refetch boom' });
  const outcome = await op;

  assert.ok(outcome.ok, 'op must resolve even when the forced refetch fails');
  assert.equal(core.state.statusOps['21'].phase, 'success');
  assert.equal(core.state.page.phase, 'error', 'truth refresh failure surfaces via page error state');
});

test('applyFilters resets page to 1 while retaining filters', async () => {
  const transport = fakeTransport();
  const core = createAdminUsersCore(transport);
  await seed(core, transport, []);
  const done = core.applyFilters({ keyword: ' 王五 ', role: 'ADMIN' });
  transport.pendingList[1].resolve({ records: [], pageNumber: 1, pageSize: 10, total: 0 });
  await done.catch(() => {});
  assert.equal(transport.calls.list[1].pageNumber, 1);
  assert.equal(transport.calls.list[1].keyword, '王五');
  assert.equal(transport.calls.list[1].role, 'ADMIN');
  assert.equal(core.state.pageNumber, 1);
  assert.equal(core.state.filters.role, 'ADMIN');
});

test('stale responses are discarded by sequence guard', async () => {
  const transport = fakeTransport();
  const core = createAdminUsersCore(transport);
  await seed(core, transport, []);

  const pStale = core.setPage(2);
  const pFresh = core.applyFilters({ keyword: '李四' });

  transport.pendingList[2].resolve({ records: [user('8')], pageNumber: 1, pageSize: 10, total: 1 });
  await pFresh.catch(() => {});
  assert.equal(core.state.page.records[0]?.id, '8');

  transport.pendingList[1].resolve({ records: [user('99')], pageNumber: 2, pageSize: 10, total: 5 });
  await pStale.catch(() => {});
  assert.ok(!core.state.page.records.some((row) => row.id === '99'), 'stale response must not overwrite newer truth');
});

test('failed load exposes retry which forces a fresh request', async () => {
  const transport = fakeTransport();
  const core = createAdminUsersCore(transport);
  const attempt = core.fetchList();
  transport.pendingList[0].reject({ response: { status: 500 }, message: 'boom' });
  await assert.rejects(attempt);
  assert.equal(core.state.page.phase, 'error');
  assert.match(core.state.page.error.adminMessage, /操作失败/);

  const retried = core.retry();
  transport.pendingList[1].resolve({ records: [user('9')], pageNumber: 1, pageSize: 10, total: 1 });
  await retried.catch(() => {});
  assert.equal(core.state.page.phase, 'success');
  assert.equal(transport.calls.list.length, 2);
});

test('empty result transitions to empty phase', async () => {
  const transport = fakeTransport();
  const core = createAdminUsersCore(transport);
  const done = core.fetchList();
  transport.pendingList[0].resolve({ records: [], pageNumber: 1, pageSize: 10, total: 0 });
  await done.catch(() => {});
  assert.equal(core.state.page.phase, 'empty');
  assert.deepEqual(core.state.page.records, []);
});

test('changeStatus sends exact numeric body once per click and applies returned UserView', async () => {
  const transport = fakeTransport();
  const core = createAdminUsersCore(transport);
  await seed(core, transport, [user('11')]);

  const op = core.changeStatus('11', '0');
  const duplicate = core.changeStatus('11', 0);
  assert.strictEqual(op, duplicate);
  assert.equal(transport.calls.updateStatus.length, 1);
  assert.deepEqual(transport.calls.updateStatus[0], { id: '11', body: { status: 0 } });

  transport.pendingStatus[0].resolve(user('11', { status: 0 }));
  await op;
  assert.equal(core.state.statusOps['11'].phase, 'success');
  assert.equal(core.state.page.records.find((row) => row.id === '11').status, 0);
  assert.ok(transport.calls.list.length >= 2, 'server truth refetched after success');
});

test('same-status PATCH sends again after settle (idempotent repeat allowed)', async () => {
  const transport = fakeTransport();
  const core = createAdminUsersCore(transport);
  await seed(core, transport, [user('12', { status: 0 })]);

  const first = core.changeStatus('12', 0);
  transport.pendingStatus[0].resolve(user('12', { status: 0 }));
  await first;

  const second = core.changeStatus('12', 0);
  assert.notStrictEqual(first, second);
  transport.pendingStatus[1].resolve(user('12', { status: 0 }));
  await second.catch(() => {});
  assert.equal(transport.calls.updateStatus.length, 2);
  assert.deepEqual(transport.calls.updateStatus[1], { id: '12', body: { status: 0 } });
});

test('self-disable 409 is actionable, preserves filters/form, refreshes server truth', async () => {
  const transport = fakeTransport();
  const core = createAdminUsersCore(transport);
  await seed(core, transport, [user('1', { role: 'ADMIN' })]);

  const op = core.changeStatus('1', 0).catch((error) => error);
  transport.pendingStatus[0].reject({
    response: { status: 409, data: { code: 41000, message: 'administrator cannot disable self' } },
    code: 41000,
  });
  const thrown = await op;

  assert.ok(thrown instanceof Error);
  const opState = core.state.statusOps['1'];
  assert.equal(opState.phase, 'error');
  assert.match(opState.adminMessage, /状态更新被拒绝|冲突/);
  assert.equal(core.state.filters.keyword, '');
  assert.deepEqual(JSON.parse(JSON.stringify(core.state.filters)), { keyword: '', role: '', status: '' });
  await Promise.resolve();
  assert.ok(transport.calls.list.length >= 2, 'server truth must be refetched after failure');
  assert.ok(!core.state.sessionCleared, 'no logout/session fabrication exists');
});

test('non-conforming PATCH payload surfaces error without mutating rows', async () => {
  const transport = fakeTransport();
  const core = createAdminUsersCore(transport);
  await seed(core, transport, [user('13')]);

  const before = JSON.stringify(core.state.page.records);
  const outcome = core.changeStatus('13', 0).then(() => null, (error) => error);
  transport.pendingStatus[0].resolve({ ...user('13'), unknownField: true });
  const thrown = await outcome;

  assert.ok(thrown instanceof Error);
  assert.equal(JSON.stringify(core.state.page.records), before);
  assert.equal(core.state.statusOps['13'].phase, 'error');
});
