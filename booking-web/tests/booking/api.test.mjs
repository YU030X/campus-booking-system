import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { createBookingApi } from '../../src/api/booking.js';

function makeTransport(handler) {
  const calls = [];
  const record = (method) => (url, bodyOrConfig, maybeConfig) => {
    const config = method === 'get' ? bodyOrConfig : maybeConfig;
    const body = method === 'get' ? undefined : bodyOrConfig;
    calls.push({ method, url, params: config?.params, body });
    return Promise.resolve(handler({ method, url, params: config?.params, body, calls }));
  };
  return {
    calls,
    get: record('get'),
    post: record('post'),
  };
}

const okEnvelope = (data) => ({ status: 200, data: { code: 0, message: 'success', data } });

const viewFixture = () => ({
  id: '9',
  bookingNo: 'BK20260826000001',
  userId: '3',
  resourceId: '7',
  startTime: '2026-08-26 09:30:00',
  endTime: '2026-08-26 10:00:00',
  purpose: '小组讨论',
  attendeeCount: 4,
  status: 'PENDING_APPROVAL',
  checkinTime: null,
  cancelTime: null,
  cancelReason: null,
  createdAt: '2026-08-26 08:00:00',
  updatedAt: '2026-08-26 08:00:00',
});

const validInput = () => ({
  resourceId: '7',
  startTime: '2026-08-26 09:30:00',
  endTime: '2026-08-26 10:00:00',
  purpose: ' 讨论 ',
  attendeeCount: 4,
});

describe('availability', () => {
  it('GET 正确路径与 date 参数并归一化载荷', async () => {
    const transport = makeTransport(() => okEnvelope({
      resourceId: '7', date: '2026-10-15', slotMinutes: 30,
      slots: [{ startTime: '08:00', endTime: '08:30', available: true }],
    }));
    const api = createBookingApi(transport);
    const payload = await api.availability('7', '2026-10-15');
    assert.equal(transport.calls[0].method, 'get');
    assert.equal(transport.calls[0].url, '/resources/7/available-slots');
    assert.deepEqual(transport.calls[0].params, { date: '2026-10-15' });
    assert.equal(payload.slots[0].startTime, '08:00');
  });
  it('非法 resourceId/date 在传输前抛错且零请求', async () => {
    const transport = makeTransport(() => okEnvelope(null));
    const api = createBookingApi(transport);
    await assert.rejects(async () => api.availability('0', '2026-10-15'), TypeError);
    await assert.rejects(async () => api.availability('7', '10/15/2026'), TypeError);
    assert.equal(transport.calls.length, 0);
  });
  it('非零 code envelope 抛错且携带 code,不伪造成功', async () => {
    const transport = makeTransport(() => ({ status: 409, data: { code: 42000, message: 'resource unavailable', data: null } }));
    const api = createBookingApi(transport);
    await assert.rejects(async () => api.availability('7', '2026-10-15'), (error) => error.code === 42000);
  });
});

describe('create', () => {
  it('POST /bookings 精确字段集并解包 BookingView', async () => {
    const transport = makeTransport(() => ({ status: 201, data: { code: 0, message: 'success', data: viewFixture() } }));
    const api = createBookingApi(transport);
    const view = await api.create(validInput());
    assert.equal(transport.calls[0].url, '/bookings');
    assert.deepEqual(Object.keys(transport.calls[0].body).sort(), ['attendeeCount', 'endTime', 'purpose', 'resourceId', 'startTime']);
    assert.equal(transport.calls[0].body.purpose, '讨论');
    assert.equal(view.id, '9');
    assert.equal(typeof view.id, 'string');
  });
  it('校验失败在传输前拦截(未知字段、attendeeCount、跨日)', async () => {
    const transport = makeTransport(() => okEnvelope(viewFixture()));
    const api = createBookingApi(transport);
    await assert.rejects(async () => api.create({ ...validInput(), status: 'CONFIRMED' }), TypeError);
    await assert.rejects(async () => api.create({ ...validInput(), attendeeCount: 0 }), TypeError);
    await assert.rejects(
      async () => api.create({ ...validInput(), startTime: '2026-08-26 23:30:00', endTime: '2026-08-27 00:00:00' }),
      /跨日/,
    );
    assert.equal(transport.calls.length, 0);
  });
});

describe('list', () => {
  it('GET /bookings 默认分页,status 仅在有值时发送', async () => {
    const transport = makeTransport(() => okEnvelope({ pageNumber: 1, pageSize: 10, total: 1, records: [viewFixture()] }));
    const api = createBookingApi(transport);
    const page = await api.list({});
    assert.deepEqual(transport.calls[0].params, { pageNumber: 1, pageSize: 10 });
    assert.equal(page.total, 1);
    await api.list({ pageNumber: 2, pageSize: 50, status: 'CONFIRMED' });
    assert.deepEqual(transport.calls[1].params, { pageNumber: 2, pageSize: 50, status: 'CONFIRMED' });
  });
  it('pageSize>100 或坏状态在传输前拒绝', async () => {
    const transport = makeTransport(() => okEnvelope(null));
    const api = createBookingApi(transport);
    await assert.rejects(async () => api.list({ pageSize: 101 }), TypeError);
    await assert.rejects(async () => api.list({ status: 'FOO' }), TypeError);
    assert.equal(transport.calls.length, 0);
  });
});

describe('detail 与 cancel', () => {
  it('detail 使用安全编码的 bookingId', async () => {
    const transport = makeTransport(() => okEnvelope(viewFixture()));
    const api = createBookingApi(transport);
    const view = await api.detail('9');
    assert.equal(transport.calls[0].url, '/bookings/9');
    assert.equal(view.userId, '3');
  });
  it('畸形 bookingId 零请求', async () => {
    const transport = makeTransport(() => okEnvelope(viewFixture()));
    const api = createBookingApi(transport);
    for (const bad of ['abc', '0', '', '../7', '9/x']) {
      await assert.rejects(async () => api.detail(bad), TypeError);
      await assert.rejects(async () => api.cancel(bad), TypeError);
    }
    assert.equal(transport.calls.length, 0);
  });
  it('cancel POST /bookings/{id}/cancel;reason 为空发 {},否则精确字段', async () => {
    const transport = makeTransport(() => okEnvelope({ ...viewFixture(), status: 'CANCELLED', cancelReason: '有事' }));
    const api = createBookingApi(transport);
    await api.cancel('9');
    assert.equal(transport.calls[0].url, '/bookings/9/cancel');
    assert.deepEqual(transport.calls[0].body, {});
    await api.cancel('9', '  有事 ');
    assert.deepEqual(transport.calls[1].body, { cancelReason: '有事' });
  });
  it('cancelReason 超 200 码点传输前拒绝', async () => {
    const transport = makeTransport(() => okEnvelope(viewFixture()));
    const api = createBookingApi(transport);
    await assert.rejects(async () => api.cancel('9', '\u{1F600}'.repeat(201)), TypeError);
    assert.equal(transport.calls.length, 0);
  });
});

