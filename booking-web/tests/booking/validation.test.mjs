import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  codePoints,
  isBookingId,
  isResourceId,
  isValidDate,
  isValidDateTime,
  normalizeBookingPage,
  normalizeBookingView,
  normalizeCancelReason,
  normalizePurpose,
  parseBookingHandoff,
  validateCreateInput,
  validatePageQuery,
} from '../../src/components/booking/validation.js';

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

describe('id 校验', () => {
  it('接受非零十进制字符串', () => {
    assert.equal(isResourceId('1'), true);
    assert.equal(isResourceId('123456789012'), true);
    assert.equal(isBookingId('9'), true);
  });
  it('拒绝零、前导零、非字符串与畸形', () => {
    for (const bad of ['0', '01', '', '12a', '-1', '+1', 12, null, undefined, ' 1']) {
      assert.equal(isResourceId(bad), false, `resourceId ${String(bad)} 应被拒绝`);
      assert.equal(isBookingId(bad), false, `bookingId ${String(bad)} 应被拒绝`);
    }
  });
});

describe('日期时间校验', () => {
  it('yyyy-MM-dd 日历合法性与格式', () => {
    assert.equal(isValidDate('2026-08-26'), true);
    assert.equal(isValidDate('2028-02-29'), true);
    assert.equal(isValidDate('2026-02-29'), false);
    assert.equal(isValidDate('2026-02-30'), false);
    assert.equal(isValidDate('2026-13-01'), false);
    assert.equal(isValidDate('2026-00-10'), false);
    assert.equal(isValidDate('2026-1-1'), false);
    assert.equal(isValidDate('20260826'), false);
    assert.equal(isValidDate(20260826), false);
  });
  it('yyyy-MM-dd HH:mm:ss 格式与时钟边界', () => {
    assert.equal(isValidDateTime('2026-08-26 09:30:00'), true);
    assert.equal(isValidDateTime('2026-08-26 23:59:59'), true);
    assert.equal(isValidDateTime('2026-08-26 24:00:00'), false);
    assert.equal(isValidDateTime('2026-08-26 09:60:00'), false);
    assert.equal(isValidDateTime('2026-08-26 09:30'), false);
    assert.equal(isValidDateTime('2026-08-26T09:30:00'), false);
    assert.equal(isValidDateTime('2026-02-30 09:30:00'), false);
  });
});

describe('purpose 与 cancelReason 归一化', () => {
  it('trim 且空白转 null', () => {
    assert.deepEqual(normalizePurpose('  复习  '), { error: null, value: '复习' });
    assert.deepEqual(normalizePurpose('   '), { error: null, value: null });
    assert.deepEqual(normalizePurpose(null), { error: null, value: null });
    assert.equal(normalizePurpose(42).error != null, true);
  });
  it('500 码点按 Unicode 码点计数(astral 字符计 1)', () => {
    const emoji = '\u{1F600}';
    assert.equal(codePoints(emoji), 1);
    assert.deepEqual(normalizePurpose(emoji.repeat(500)), { error: null, value: emoji.repeat(500) });
    assert.equal(normalizePurpose(`${emoji.repeat(500)}x`).error != null, true);
    assert.equal(codePoints(emoji.repeat(501)), 501);
  });
  it('cancelReason 上限 200 码点', () => {
    const emoji = '\u{1F600}';
    assert.deepEqual(normalizeCancelReason(emoji.repeat(200)).value, emoji.repeat(200));
    assert.equal(normalizeCancelReason(emoji.repeat(201)).error != null, true);
    assert.deepEqual(normalizeCancelReason('   '), { error: null, value: null });
    assert.equal(normalizeCancelReason(undefined).value, null);
  });
});

describe('创建输入白名单与序列化', () => {
  const base = {
    resourceId: '7',
    startTime: '2026-08-26 09:30:00',
    endTime: '2026-08-26 10:00:00',
    purpose: ' 讨论 ',
    attendeeCount: 2,
  };
  it('精确字段集且 purpose 已归一化', () => {
    const result = validateCreateInput(base);
    assert.equal(result.valid, true);
    assert.deepEqual(Object.keys(result.value).sort(), ['attendeeCount', 'endTime', 'purpose', 'resourceId', 'startTime']);
    assert.equal(result.value.purpose, '讨论');
  });
  it('拒绝未知字段', () => {
    const result = validateCreateInput({ ...base, status: 'CONFIRMED' });
    assert.equal(result.valid, false);
    assert.match(result.errors[0], /未知字段/);
  });
  it('拒绝非法字段值', () => {
    assert.equal(validateCreateInput({ ...base, resourceId: '0' }).valid, false);
    assert.equal(validateCreateInput({ ...base, attendeeCount: 0 }).valid, false);
    assert.equal(validateCreateInput({ ...base, attendeeCount: 1.5 }).valid, false);
    assert.equal(validateCreateInput({ ...base, startTime: '2026-08-26 09:30' }).valid, false);
    assert.equal(validateCreateInput(null).valid, false);
    assert.equal(validateCreateInput([base]).valid, false);
  });
});

describe('安全 query handoff', () => {
  it('仅接受精确的 resourceId/date 同源路由参数', () => {
    assert.deepEqual(
      parseBookingHandoff({ resourceId: '7', date: '2026-08-26' }),
      { resourceId: '7', date: '2026-08-26' },
    );
    assert.equal(parseBookingHandoff({ resourceId: '7', date: '2026-02-30' }), null);
    assert.equal(parseBookingHandoff({ resourceId: 'javascript:alert(1)', date: '2026-08-26' }), null);
    assert.equal(parseBookingHandoff({ resourceId: '7', date: '2026-08-26', origin: 'https://evil.example' }), null);
    assert.equal(parseBookingHandoff({ resourceId: ['7'], date: '2026-08-26' }), null);
  });
});

describe('分页与状态过滤', () => {
  it('默认值与显式状态', () => {
    assert.deepEqual(validatePageQuery(undefined).value, { pageNumber: 1, pageSize: 10, status: null });
    assert.deepEqual(validatePageQuery({ pageNumber: 2, pageSize: 100 }).value, { pageNumber: 2, pageSize: 100, status: null });
    assert.equal(validatePageQuery({ pageSize: 101 }).valid, false);
    assert.equal(validatePageQuery({ pageSize: 0 }).valid, false);
    assert.equal(validatePageQuery({ pageNumber: 0 }).valid, false);
    assert.equal(validatePageQuery({ status: 'PENDING_APPROVAL' }).value.status, 'PENDING_APPROVAL');
    assert.equal(validatePageQuery({ status: '' }).value.status, null);
    assert.equal(validatePageQuery({ status: 'pending_approval' }).valid, false);
    assert.equal(validatePageQuery({ status: 'FOO' }).valid, false);
  });
});

describe('BookingView 归一化', () => {
  it('完整视图通过且字段精确', () => {
    const result = normalizeBookingView(viewFixture());
    assert.equal(result.valid, true);
    assert.deepEqual(Object.keys(result.value), Object.keys(viewFixture()));
  });
  it('拒绝未知字段与非字符串 Long ID', () => {
    const extra = normalizeBookingView({ ...viewFixture(), extra: 1 });
    assert.match(extra.errors.join('; '), /未知字段/);
    const numeric = normalizeBookingView({ ...viewFixture(), id: 9 });
    assert.match(numeric.errors.join('; '), /id/);
  });
  it('拒绝非法状态与空 bookingNo', () => {
    assert.equal(normalizeBookingView({ ...viewFixture(), status: 'FOO' }).valid, false);
    assert.equal(normalizeBookingView({ ...viewFixture(), bookingNo: '' }).valid, false);
    assert.equal(normalizeBookingView({ ...viewFixture(), attendeeCount: 0 }).valid, false);
    assert.equal(normalizeBookingView({ ...viewFixture(), createdAt: '2026-08-26T08:00:00' }).valid, false);
  });
  it('可空字段允许 null 但不允许坏格式', () => {
    assert.equal(normalizeBookingView({ ...viewFixture(), cancelTime: '2026-08-26 11:00:00', cancelReason: '有事' }).valid, true);
    assert.equal(normalizeBookingView({ ...viewFixture(), cancelTime: 'not-a-time' }).valid, false);
  });
  it('分页结果归一化逐条校验', () => {
    const good = normalizeBookingPage({ pageNumber: 1, pageSize: 10, total: 1, records: [viewFixture()] });
    assert.equal(good.valid, true);
    assert.equal(good.value.records.length, 1);
    assert.equal(normalizeBookingPage({ pageNumber: 1, pageSize: 10, total: -1, records: [] }).valid, false);
    assert.equal(normalizeBookingPage({ pageNumber: 1, pageSize: 10, total: 1, records: [{ ...viewFixture(), id: '' }] }).valid, false);
  });
});
