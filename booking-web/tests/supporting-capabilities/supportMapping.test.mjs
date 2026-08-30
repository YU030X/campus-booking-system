import test from 'node:test';
import assert from 'node:assert/strict';
import {
  mapBookingStatistics,
  mapNotificationPage,
  mapResourceStatistics,
  mapSupportError,
  requireDate,
} from '../../src/api/supportCore.js';

test('notification pages preserve canonical string IDs and read state', () => {
  const page = mapNotificationPage({
    records: [{ id: '9', userId: '5', title: '预约已通过', content: '内容', type: 'BOOKING_APPROVED', bizId: '7', isRead: 0, createdAt: '2026-08-30 12:00:00' }],
    pageNumber: 1,
    pageSize: 10,
    total: 1,
  });
  assert.equal(page.records[0].id, '9');
  assert.equal(page.records[0].bizId, '7');
  assert.equal(page.records[0].isRead, false);
  assert.throws(() => mapNotificationPage({ ...page, records: [{ ...page.records[0], id: 9 }] }), /id/);
  assert.throws(() => mapNotificationPage({ ...page, records: [{ ...page.records[0], isRead: false }] }), /状态/);
});

test('statistics mappings accept null usage rates and reject malformed aggregates', () => {
  const resources = mapResourceStatistics({
    fromDate: '2026-08-01', toDate: '2026-08-30',
    records: [{ resourceId: '7', resourceName: 'A301', bookingCount: 2, completedCount: 1, cancelledCount: 1, noShowCount: 0, occupiedSlotMinutes: 90, usageRate: null }],
  });
  assert.equal(resources.records[0].usageRate, null);
  const bookings = mapBookingStatistics({
    fromDate: '2026-08-01', toDate: '2026-08-30', records: [{ status: 'CONFIRMED', count: 2 }],
  });
  assert.equal(bookings.records[0].count, 2);
  assert.throws(() => mapResourceStatistics({ ...resources, records: [{ ...resources.records[0], usageRate: 1.1 }] }), /usageRate/);
});

test('date and support error contracts remain deterministic', () => {
  assert.equal(requireDate('2026-08-30', 'fromDate'), '2026-08-30');
  assert.throws(() => requireDate('2026-02-30', 'fromDate'), /有效日期/);
  const error = Object.assign(new Error('transport'), { response: { status: 404, data: { code: 40400 } }, code: 40400 });
  assert.strictEqual(mapSupportError(error), error);
  assert.match(error.supportMessage, /未启用|不存在/);
});
