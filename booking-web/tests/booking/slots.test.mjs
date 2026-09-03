import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  buildSelection,
  isPastSlot,
  normalizeAvailabilityPayload,
  validateInterval,
} from '../../src/components/booking/slots.js';

const slot = (start, end, available = true) => ({ startTime: start, endTime: end, available });

const payloadFixture = () => ({
  resourceId: '7',
  date: '2026-10-15',
  slotMinutes: 30,
  slots: [
    slot('08:00', '08:30'),
    slot('08:30', '09:00', false),
    slot('09:00', '09:30'),
    slot('09:30', '10:00'),
  ],
});

describe('可用时段载荷归一化', () => {
  it('合法载荷通过且字段精确', () => {
    const normalized = normalizeAvailabilityPayload(payloadFixture());
    assert.deepEqual(Object.keys(normalized), ['resourceId', 'date', 'slotMinutes', 'slots']);
    assert.equal(normalized.slotMinutes, 30);
    assert.equal(normalized.slots.length, 4);
    assert.equal(normalized.slots[1].available, false);
  });
  it('拒绝 slotMinutes != 30', () => {
    assert.throws(() => normalizeAvailabilityPayload({ ...payloadFixture(), slotMinutes: 15 }), /slotMinutes/);
    assert.throws(() => normalizeAvailabilityPayload({ ...payloadFixture(), slotMinutes: '30' }), /slotMinutes/);
  });
  it('拒绝未知字段与非对齐时间', () => {
    assert.throws(() => normalizeAvailabilityPayload({ ...payloadFixture(), extra: 1 }), /未知字段/);
    assert.throws(
      () => normalizeAvailabilityPayload({ ...payloadFixture(), slots: [slot('08:10', '08:40')] }),
      /对齐/,
    );
    assert.throws(
      () => normalizeAvailabilityPayload({ ...payloadFixture(), slots: [{ ...slot('08:00', '08:30'), note: 'x' }] }),
      /未知字段/,
    );
  });
  it('拒绝坏 resourceId/date、非布尔 available、乱序与倒置区间', () => {
    assert.throws(() => normalizeAvailabilityPayload({ ...payloadFixture(), resourceId: 7 }), /resourceId/);
    assert.throws(() => normalizeAvailabilityPayload({ ...payloadFixture(), date: '2026/10/15' }), /date/);
    assert.throws(() => normalizeAvailabilityPayload({ ...payloadFixture(), slots: [slot('08:00', '08:30', 'yes')] }), /布尔/);
    assert.throws(() => normalizeAvailabilityPayload({ ...payloadFixture(), slots: [slot('09:00', '09:30'), slot('08:00', '08:30')] }), /升序/);
    assert.throws(() => normalizeAvailabilityPayload(null), TypeError);
  });
  it('空 slots 合法(闭馆日)', () => {
    const normalized = normalizeAvailabilityPayload({ ...payloadFixture(), slots: [] });
    assert.deepEqual(normalized.slots, []);
  });
});

describe('过去时段判断', () => {
  it('按 Asia/Shanghai 格式化后的当前时间阻止过去与当前 slot', () => {
    assert.equal(isPastSlot('2026-10-15', '08:00', '2026-10-15 08:00:00'), true);
    assert.equal(isPastSlot('2026-10-15', '08:00', '2026-10-15 08:01:00'), true);
    assert.equal(isPastSlot('2026-10-15', '08:30', '2026-10-15 08:01:00'), false);
    assert.equal(isPastSlot('2026-10-15', '08:00', 'bad'), true);
  });
});

describe('连续选择推导(左闭右开)', () => {
  it('相邻两个可用时段推导 start/end 与时长', () => {
    const result = buildSelection('2026-10-15', [slot('08:00', '08:30'), slot('08:30', '09:00')]);
    assert.equal(result.valid, true);
    assert.equal(result.value.startTime, '2026-10-15 08:00:00');
    assert.equal(result.value.endTime, '2026-10-15 09:00:00');
    assert.equal(result.value.slotCount, 2);
    assert.equal(result.value.durationMinutes, 60);
  });
  it('结束边界不作为占用时段(slotCount 由所选数量决定)', () => {
    const result = buildSelection('2026-10-15', [
      slot('08:00', '08:30'),
      slot('08:30', '09:00'),
      slot('09:00', '09:30'),
      slot('09:30', '10:00'),
    ]);
    assert.equal(result.value.slotCount, 4);
    assert.equal(result.value.endTime, '2026-10-15 10:00:00');
    assert.equal(result.value.durationMinutes, 120);
  });
  it('拒绝不连续、乱序、含不可用、空选择', () => {
    assert.equal(buildSelection('2026-10-15', [slot('08:00', '08:30'), slot('09:00', '09:30')]).valid, false);
    assert.equal(buildSelection('2026-10-15', [slot('08:30', '09:00'), slot('08:00', '08:30')]).valid, false);
    assert.equal(buildSelection('2026-10-15', [slot('08:00', '08:30', false)]).valid, false);
    assert.equal(buildSelection('2026-10-15', []).valid, false);
    assert.equal(buildSelection('2026-10-15', null).valid, false);
  });
});

describe('区间校验(同日、对齐、秒为零)', () => {
  it('合法半小时区间通过', () => {
    assert.equal(validateInterval({ startTime: '2026-10-15 14:00:00', endTime: '2026-10-15 16:00:00' }).valid, true);
  });
  it('拒绝跨日、非对齐、秒非零、倒置', () => {
    assert.equal(validateInterval({ startTime: '2026-10-15 23:30:00', endTime: '2026-10-16 00:00:00' }).valid, false);
    assert.equal(validateInterval({ startTime: '2026-10-15 14:10:00', endTime: '2026-10-15 15:00:00' }).valid, false);
    assert.equal(validateInterval({ startTime: '2026-10-15 14:00:01', endTime: '2026-10-15 15:00:00' }).valid, false);
    assert.equal(validateInterval({ startTime: '2026-10-15 15:00:00', endTime: '2026-10-15 14:00:00' }).valid, false);
    assert.equal(validateInterval({ startTime: 'bad', endTime: '2026-10-15 15:00:00' }).valid, false);
  });
});
