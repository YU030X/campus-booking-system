import test from 'node:test';
import assert from 'node:assert/strict';
import { createAdminApprovalsCore } from '../../src/api/adminApprovals.js';

const booking = (id, overrides = {}) => ({
  id,
  bookingNo: `BK${String(id).padStart(9, '0')}`,
  userId: '42',
  resourceId: '7',
  startTime: '2026-08-28 09:00:00',
  endTime: '2026-08-28 10:30:00',
  purpose: '例会',
  attendeeCount: 3,
  status: 'PENDING_APPROVAL',
  checkinTime: null,
  cancelTime: null,
  cancelReason: null,
  createdAt: '2026-08-27 08:00:00',
  updatedAt: '2026-08-27 08:00:00',
  ...overrides,
});

const deferred = () => {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
};

function fakeTransport() {
  const calls = { list: [], action: [] };
  const pendingList = [];
  const pendingAction = [];
  return {
    calls,
    pendingList,
    pendingAction,
    list(params) {
      calls.list.push(params);
      const d = deferred();
      pendingList.push(d);
      return d.promise;
    },
    action(id, name, body) {
      calls.action.push({ id, name, body });
      const d = deferred();
      pendingAction.push(d);
      return d.promise;
    },
  };
}

async function seed(core, transport, rows) {
  const done = core.fetchList();
  transport.pendingList[0].resolve({ records: rows, pageNumber: 1, pageSize: 10, total: rows.length });
  await done;
}

test('identical concurrent fetches share one request and still apply the result', async () => {
  const transport = fakeTransport();
  const core = createAdminApprovalsCore(transport);
  const first = core.fetchList();
  const second = core.fetchList();
  assert.strictEqual(first, second);
  transport.pendingList[0].resolve({ records: [booking('301')], pageNumber: 1, pageSize: 10, total: 1 });
  await Promise.all([first, second]);
  assert.equal(transport.calls.list.length, 1);
  assert.equal(core.state.page.phase, 'success');
});

test('page changes race safely; late stale responses never overwrite newer truth', async () => {
  const transport = fakeTransport();
  const core = createAdminApprovalsCore(transport);
  await seed(core, transport, []);
  const pStale = core.setPage(2);
  const pFresh = core.setPage(3);
  transport.pendingList[2].resolve({ records: [booking('505')], pageNumber: 3, pageSize: 10, total: 8 });
  await pFresh.catch(() => {});
  transport.pendingList[1].resolve({ records: [booking('404')], pageNumber: 2, pageSize: 10, total: 8 });
  await pStale.catch(() => {});
  assert.equal(core.state.page.pageNumber, 3);
  assert.ok(!core.state.page.records.some((row) => row.id === '404'));
});

test('selected detail derives from list rows (source=list) or is overwritten by action results', async () => {
  const transport = fakeTransport();
  const core = createAdminApprovalsCore(transport);
  assert.equal(core.state.selected.phase, 'none');
  assert.equal(core.state.selected.source, null);

  const picked = core.setSelectedFrom(booking('301'));
  assert.equal(picked.phase, 'ready');
  assert.equal(picked.source, 'list');
  assert.equal(picked.booking.id, '301');

  core.clearSelection();
  assert.equal(core.state.selected.phase, 'none');
  assert.equal(core.state.selected.source, null);
});

test('approve sets an unconditional action-sourced terminal selection', async () => {
  const transport = fakeTransport();
  const core = createAdminApprovalsCore(transport);
  await seed(core, transport, [booking('306')]);
  assert.equal(core.state.selected.phase, 'none', 'no pre-selection required');

  const op = core.requestAction('306', 'approve', null);
  transport.pendingAction[0].resolve(booking('306', { status: 'APPROVED' }));
  transport.pendingList[1].resolve({ records: [], pageNumber: 1, pageSize: 10, total: 0 });
  await op.catch(() => {});

  assert.equal(core.state.selected.phase, 'ready');
  assert.equal(core.state.selected.source, 'action');
  assert.equal(core.state.selected.booking.status, 'APPROVED');
});

test('pending refresh clears stale list-source selection and never drops action-terminal detail', async () => {
  const transport = fakeTransport();
  const core = createAdminApprovalsCore(transport);
  await seed(core, transport, [booking('401'), booking('402')]);

  core.setSelectedFrom(booking({ id: '401', bookingNo: 'BK000000401', attendeeCount: 5 }));
  const gone = core.refreshTruth();
  transport.pendingList[1].resolve({
    records: [{ ...booking('402') }],
    pageNumber: 1,
    pageSize: 10,
    total: 1,
  });
  await gone.catch(() => {});
  assert.equal(core.state.selected.phase, 'none', 'stale pending selection removed once item disappears');
  assert.equal(core.state.selected.source, null);

  const op = core.requestAction('402', 'approve', null);
  transport.pendingAction[0].resolve(booking('402', { status: 'APPROVED' }));
  transport.pendingList[2].resolve({ records: [], pageNumber: 1, pageSize: 10, total: 0 });
  await op.catch(() => {});
  assert.equal(core.state.selected.source, 'action');
  assert.equal(core.state.selected.booking.status, 'APPROVED');

  const laterRefresh = core.refreshTruth();
  transport.pendingList[3].resolve({ records: [booking('499')], pageNumber: 1, pageSize: 10, total: 1 });
  await laterRefresh.catch(() => {});
  assert.equal(core.state.selected.phase, 'ready', 'list refresh must not clear action-terminal detail');
  assert.equal(core.state.selected.source, 'action');
  assert.equal(core.state.selected.booking.id, '402');
});

test('pending refresh updates row values of a still-present list-source selection', async () => {
  const transport = fakeTransport();
  const core = createAdminApprovalsCore(transport);
  await seed(core, transport, [booking('501')]);

  core.setSelectedFrom(booking('501'));
  assert.equal(core.state.selected.source, 'list');

  const refreshed = core.refreshTruth();
  transport.pendingList[1].resolve({
    records: [booking('501', { attendeeCount: 9 })],
    pageNumber: 1,
    pageSize: 10,
    total: 1,
  });
  await refreshed.catch(() => {});
  assert.equal(core.state.selected.phase, 'ready');
  assert.equal(core.state.selected.source, 'list');
  assert.equal(core.state.selected.booking.attendeeCount, 9, 'list-source selection follows refreshed truth');
});

test('approve sends exact trimmed/comment-null body once per identical click', async () => {
  const transport = fakeTransport();
  const core = createAdminApprovalsCore(transport);
  await seed(core, transport, [booking('301')]);

  const blankCommentOp = core.requestAction('301', 'approve', '   ').catch((error) => error);
  const sameKeyShared = core.requestAction('301', 'approve', '   ');
  void sameKeyShared;
  transport.pendingAction[0].resolve(booking('301', { status: 'APPROVED' }));
  const outcome = await blankCommentOp;
  assert.ok(outcome && outcome.status === 'APPROVED');
  assert.deepEqual(transport.calls.action[0], { id: '301', name: 'approve', body: { comment: null } });
  assert.equal(transport.calls.action.length, 1);

  const commented = core.requestAction('301', 'approve', ' 紧急场地 ');
  transport.pendingAction[1].resolve(booking('301', { status: 'APPROVED' }));
  await commented;
  assert.deepEqual(
    transport.calls.action[1],
    { id: '301', name: 'approve', body: { comment: '紧急场地' } },
  );
});

test('reject validates client-side and posts trimmed required comment', async () => {
  const transport = fakeTransport();
  const core = createAdminApprovalsCore(transport);
  await seed(core, transport, [booking('302')]);

  await assert.rejects(core.requestAction('302', 'reject', '   '), /必填/);
  assert.equal(transport.calls.action.length, 0, 'invalid reject must block the request');

  const op = core.requestAction('302', 'reject', ' 与课程冲突 ');
  transport.pendingAction[0].resolve(booking('302', { status: 'REJECTED' }));
  await op.catch(() => {});
  assert.deepEqual(
    transport.calls.action[0],
    { id: '302', name: 'reject', body: { comment: '与课程冲突' } },
  );
});

test('action success settles first, updates selected detail, tolerates refetch failure', async () => {
  const transport = fakeTransport();
  const core = createAdminApprovalsCore(transport);
  await seed(core, transport, [booking('303')]);
  core.setSelectedFrom(booking('303'));

  const op = core.requestAction('303', 'approve', null).then(
    (value) => ({ ok: true, value }),
    (error) => ({ ok: false, error }),
  );
  transport.pendingAction[0].resolve(booking('303', { status: 'APPROVED' }));
  transport.pendingList[1].reject({ response: { status: 500 }, message: 'refetch boom' });
  const outcome = await op;

  assert.ok(outcome.ok);
  assert.equal(core.state.actions['303'].phase, 'success');
  assert.equal(core.state.selected.source, 'action');
  assert.equal(core.state.selected.booking.status, 'APPROVED');
  assert.equal(core.state.page.phase, 'error');
  assert.ok(transport.calls.list.length >= 2);
});

test('action failure keeps selected/input flow actionable and refetches truth without fabricating success', async () => {
  const transport = fakeTransport();
  const core = createAdminApprovalsCore(transport);
  await seed(core, transport, [booking('304')]);
  core.setSelectedFrom(booking('304'));

  const outcome = core.requestAction('304', 'approve', null).then(
    (value) => ({ ok: true, value }),
    (error) => ({ ok: false, error }),
  );
  transport.pendingAction[0].reject({
    response: { status: 409, data: { code: 43000, message: 'booking already approved' } },
    code: 43000,
  });
  const settled = await outcome;

  assert.ok(!settled.ok);
  const opState = core.state.actions['304'];
  assert.equal(opState.phase, 'error');
  assert.match(opState.adminMessage, /状态冲突|冲突/);
  assert.equal(core.state.selected.booking.id, '304');
  assert.ok(!core.state.sessionCleared);
  await Promise.resolve();
  assert.ok(transport.calls.list.length >= 2, 'pending list truth must be refetched after failure');
});

test('same click shares promise; opposite concurrent action is refused until settle', async () => {
  const transport = fakeTransport();
  const core = createAdminApprovalsCore(transport);
  await seed(core, transport, [booking('305')]);

  const approve = core.requestAction('305', 'approve', null);
  const shared = core.requestAction('305', 'approve', null);
  assert.strictEqual(approve, shared);
  assert.throws(() => core.requestAction('305', 'reject', 'no'), /进行中/);

  transport.pendingAction[0].resolve(booking('305', { status: 'APPROVED' }));
  await approve;

  const rejectAfterSettle = core.requestAction('305', 'reject', 'too late');
  transport.pendingAction[1].reject({
    response: { status: 409, data: { code: 43000, message: 'illegal transition' } },
    code: 43000,
  });
  await assert.rejects(rejectAfterSettle, () => true);
  assert.equal(transport.calls.action.length, 2);
});
