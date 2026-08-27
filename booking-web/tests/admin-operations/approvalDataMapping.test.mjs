import test from 'node:test';
import assert from 'node:assert/strict';
import { validateBookingView, mapPendingPage, BOOKING_VIEW_FIELDS } from '../../src/api/adminApprovals.js';

const booking = (overrides = {}) => ({
  id: '301',
  bookingNo: 'BK20260827001',
  userId: '42',
  resourceId: '7',
  startTime: '2026-08-28 09:00:00',
  endTime: '2026-08-28 10:30:00',
  purpose: '社团例会',
  attendeeCount: 12,
  status: 'PENDING_APPROVAL',
  checkinTime: null,
  cancelTime: null,
  cancelReason: null,
  createdAt: '2026-08-27 08:00:00',
  updatedAt: '2026-08-27 08:00:00',
  ...overrides,
});

test('BookingView contract freezes exactly 14 fields', () => {
  assert.deepEqual([...BOOKING_VIEW_FIELDS].sort(), [
    'attendeeCount', 'bookingNo', 'cancelReason', 'cancelTime', 'checkinTime',
    'createdAt', 'endTime', 'id', 'purpose', 'resourceId', 'startTime', 'status',
    'updatedAt', 'userId',
  ]);
});

test('validateBookingView passes a conforming pending record verbatim', () => {
  const record = validateBookingView(booking());
  assert.equal(record.id, '301');
  assert.equal(record.status, 'PENDING_APPROVAL');
});

test('ApprovalView fields must not leak into BookingView payloads', () => {
  const polluted = booking();
  polluted.approverId = '1';
  assert.throws(() => validateBookingView(polluted), /未知字段/);
  const missingOne = booking();
  delete missingOne.purpose;
  assert.throws(() => validateBookingView(missingOne), /缺少字段/);
});

test('Long ids stay strings; numeric ids are drift', () => {
  const numericId = booking({ id: 301 });
  assert.throws(() => validateBookingView(numericId));
  const numericUser = booking({ userId: 42 });
  assert.throws(() => validateBookingView(numericUser), /userId/);
});

test('timestamps enforce yyyy-MM-dd HH:mm:ss; nullable times accept null only', () => {
  assert.throws(() => validateBookingView(booking({ startTime: '2026-08-28T09:00:00' })), /startTime/);
  assert.throws(() => validateBookingView(booking({ updatedAt: 1756000000 })), /updatedAt/);
  assert.doesNotThrow(() => validateBookingView(booking({ cancelTime: '2026-08-27 09:30:00' })));
  assert.throws(() => validateBookingView(booking({ checkinTime: '' })), /checkinTime/);
});

test('status must be exactly one of the seven frozen values', () => {
  assert.doesNotThrow(() => validateBookingView(booking({ status: 'CHECKED_IN' })));
  assert.doesNotThrow(() => validateBookingView(booking({ status: 'NO_SHOW' })));
  for (const bad of ['pending_approval', 'FOO', 'APPROVED2', 1, null]) {
    assert.throws(() => validateBookingView(booking({ status: bad })), /status/, `expected rejection: ${String(bad)}`);
  }
});

test('attendeeCount must be an integer >=1', () => {
  for (const bad of [0, -1, 2.5, '12']) {
    assert.throws(() => validateBookingView(booking({ attendeeCount: bad })), /attendeeCount/);
  }
});

test('purpose must be string or null', () => {
  assert.equal(validateBookingView(booking()).purpose, '社团例会');
  assert.doesNotThrow(() => validateBookingView(booking({ purpose: null })));
  assert.throws(() => validateBookingView(booking({ purpose: 42 })), /purpose/);
});

test('mapPendingPage preserves server order strictly and canonicalizes totals', () => {
  const page = mapPendingPage({
    records: [booking({ id: '9' }), booking({ id: '10' }), booking({ id: '11' })],
    pageNumber: '3',
    pageSize: 20,
    total: '61',
  });
  assert.deepEqual(page.records.map((row) => row.id), ['9', '10', '11'], 'server order must be kept');
  assert.deepEqual(
    { pageNumber: page.pageNumber, pageSize: page.pageSize, total: page.total },
    { pageNumber: 3, pageSize: 20, total: 61 },
  );
});

test('malformed PageResult payloads are rejected instead of faked as empty', () => {
  assert.throws(() => mapPendingPage(null), /PageResult/);
  assert.throws(() => mapPendingPage({}), /records/);
  assert.throws(() => mapPendingPage({ records: [] }), /pageNumber|total/);
  assert.throws(() => mapPendingPage({ records: [], pageNumber: 0, pageSize: 10, total: 0 }), /pageNumber/);
  assert.throws(() => mapPendingPage({ records: [], pageNumber: 1, pageSize: 101, total: 0 }), /pageSize/);
  assert.throws(() => mapPendingPage({
    records: [booking({ id: 5 })],
    pageNumber: 1,
    pageSize: 10,
    total: 1,
  }));
});
