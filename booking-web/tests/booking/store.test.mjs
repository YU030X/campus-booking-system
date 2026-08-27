import assert from 'node:assert/strict';
import { afterEach, beforeEach, describe, test } from 'node:test';
import { createPinia, setActivePinia } from 'pinia';
import {
  setBookingApiProvider,
  useBookingStore,
} from '../../src/stores/booking.js';

const booking = (overrides = {}) => ({
  id: '101',
  bookingNo: 'BK202608260001',
  userId: '11',
  resourceId: '21',
  startTime: '2026-08-27 10:00:00',
  endTime: '2026-08-27 11:00:00',
  purpose: null,
  attendeeCount: 1,
  status: 'CONFIRMED',
  checkinTime: null,
  cancelTime: null,
  cancelReason: null,
  createdAt: '2026-08-26 18:00:00',
  updatedAt: '2026-08-26 18:00:00',
  ...overrides,
});

const availability = {
  resourceId: '21',
  date: '2026-08-27',
  slotMinutes: 30,
  slots: [
    { startTime: '10:00', endTime: '10:30', available: true },
    { startTime: '10:30', endTime: '11:00', available: true },
  ],
};

beforeEach(() => {
  setActivePinia(createPinia());
});

afterEach(() => {
  setBookingApiProvider();
});

describe('booking store', () => {
  test('重复创建激活只发送一次并刷新列表和对应日期时段', async () => {
    const calls = [];
    let releaseCreate;
    const createGate = new Promise((resolve) => { releaseCreate = resolve; });
    const api = {
      async create() { calls.push('create'); await createGate; return booking(); },
      async list() {
        calls.push('list');
        return { records: [booking()], pageNumber: 1, pageSize: 10, total: 1 };
      },
      async availability() { calls.push('availability'); return availability; },
    };
    setBookingApiProvider(async () => api);
    const store = useBookingStore();
    const payload = {
      resourceId: '21',
      startTime: '2026-08-27 10:00:00',
      endTime: '2026-08-27 11:00:00',
      purpose: null,
      attendeeCount: 1,
    };
    const first = store.createBooking(payload);
    const second = store.createBooking(payload);
    await new Promise((resolve) => setTimeout(resolve, 0));
    assert.equal(calls.filter((call) => call === 'create').length, 1);
    assert.equal(store.create.pending, true);
    releaseCreate();
    const [firstResult, secondResult] = await Promise.all([first, second]);
    assert.equal(firstResult.id, '101');
    assert.equal(secondResult.id, '101');
    assert.equal(calls.filter((call) => call === 'list').length, 1);
    assert.equal(calls.filter((call) => call === 'availability').length, 1);
    assert.equal(store.create.status, 'success');
  });

  test('创建成功不会被后续刷新失败改写为失败', async () => {
    setBookingApiProvider(async () => ({
      async create() { return booking(); },
      async list() { throw new Error('list refresh failed'); },
      async availability() { return availability; },
    }));
    const store = useBookingStore();
    const result = await store.createBooking({
      resourceId: '21',
      startTime: '2026-08-27 10:00:00',
      endTime: '2026-08-27 11:00:00',
      purpose: null,
      attendeeCount: 1,
    });
    assert.equal(result.id, '101');
    assert.equal(store.create.status, 'success');
    assert.equal(store.list.status, 'error');
  });

  test('列表保持分页和精确状态，空页进入 empty', async () => {
    let captured;
    setBookingApiProvider(async () => ({
      async list(params) {
        captured = params;
        return { records: [], pageNumber: 2, pageSize: 20, total: 0 };
      },
    }));
    const store = useBookingStore();
    await store.fetchList({ pageNumber: 2, pageSize: 20, status: 'CANCELLED' });
    assert.deepEqual(captured, { pageNumber: 2, pageSize: 20, status: 'CANCELLED' });
    assert.equal(store.list.status, 'empty');
    assert.equal(store.pageResult.pageNumber, 2);
  });

  test('乱序详情响应不会用旧记录覆盖当前路由记录', async () => {
    const releases = {};
    setBookingApiProvider(async () => ({
      detail(id) {
        return new Promise((resolve) => { releases[id] = resolve; });
      },
    }));
    const store = useBookingStore();
    const first = store.fetchDetail('101', { force: true });
    const second = store.fetchDetail('102', { force: true });
    await new Promise((resolve) => setTimeout(resolve, 0));
    releases['102'](booking({ id: '102', bookingNo: 'BK202608260002' }));
    await second;
    releases['101'](booking());
    await first;
    assert.equal(store.detail.data.id, '102');
  });

  test('非法详情 ID 在传输前失败并进入可重试错误态', async () => {
    let calls = 0;
    setBookingApiProvider(async () => ({
      async detail() { calls += 1; throw new TypeError('bookingId 必须是十进制字符串'); },
    }));
    const store = useBookingStore();
    await assert.rejects(store.fetchDetail('javascript:alert(1)'));
    assert.equal(calls, 1);
    assert.equal(store.detail.status, 'error');
    assert.equal(store.detail.error.kind, 'INVALID_INPUT');
  });

  test('取消成功不会被后续刷新失败改写为失败', async () => {
    const cancelled = booking({ status: 'CANCELLED', cancelTime: '2026-08-26 18:10:00' });
    setBookingApiProvider(async () => ({
      async cancel() { return cancelled; },
      async list() { throw new Error('list refresh failed'); },
      async availability() { throw new Error('availability refresh failed'); },
    }));
    const store = useBookingStore();
    const result = await store.cancelBooking('101');
    assert.equal(result.status, 'CANCELLED');
    assert.equal(store.cancel.status, 'success');
  });

  test('取消成功更新详情并刷新列表和受影响时段', async () => {
    const calls = [];
    const cancelled = booking({
      status: 'CANCELLED',
      cancelTime: '2026-08-26 18:10:00',
      cancelReason: '计划调整',
      updatedAt: '2026-08-26 18:10:00',
    });
    setBookingApiProvider(async () => ({
      async cancel() { calls.push('cancel'); return cancelled; },
      async list() {
        calls.push('list');
        return { records: [cancelled], pageNumber: 1, pageSize: 10, total: 1 };
      },
      async availability() { calls.push('availability'); return availability; },
    }));
    const store = useBookingStore();
    store.detail.data = booking();
    await store.cancelBooking('101', '计划调整');
    assert.equal(store.detail.data.status, 'CANCELLED');
    assert.deepEqual(calls, ['cancel', 'list', 'availability']);
  });
});
