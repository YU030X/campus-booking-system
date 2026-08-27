import {
  codePoints,
  fail,
  isResourceId,
  isValidDate,
  isValidDateTime,
  ok,
} from './validation.js';

export const SLOT_MINUTES = 30;
const SLOT_TIME_RE = /^([01]\d|2[0-3]):([0-5]\d)$/;
const AVAILABILITY_KEYS = ['resourceId', 'date', 'slotMinutes', 'slots'];
const SLOT_KEYS = ['startTime', 'endTime', 'available'];

const toMinutes = (time) => {
  const m = SLOT_TIME_RE.exec(time);
  return Number(m[1]) * 60 + Number(m[2]);
};

export const isSlotTime = (value) => typeof value === 'string' && SLOT_TIME_RE.test(value);

export function isPastSlot(date, startTime, nowDateTime) {
  if (!isValidDate(date) || !isSlotTime(startTime) || !isValidDateTime(nowDateTime)) return true;
  return `${date} ${startTime}:00` <= nowDateTime;
}

function normalizeSlots(slots) {
  if (!Array.isArray(slots)) return { error: 'slots 必须是数组' };
  const normalized = [];
  let previousStart = null;
  for (const slot of slots) {
    if (slot == null || typeof slot !== 'object' || Array.isArray(slot)) {
      return { error: 'slot 必须是对象' };
    }
    const unknown = Object.keys(slot).filter((key) => !SLOT_KEYS.includes(key));
    if (unknown.length > 0) return { error: `slot 存在未知字段: ${unknown.join(', ')}` };
    if (!isSlotTime(slot.startTime) || !isSlotTime(slot.endTime)) {
      return { error: 'slot 时间必须是 HH:mm 且对齐 :00/:30' };
    }
    if (Number(slot.startTime.slice(3)) % SLOT_MINUTES !== 0 || Number(slot.endTime.slice(3)) % SLOT_MINUTES !== 0) {
      return { error: 'slot 时间必须对齐 :00/:30' };
    }
    if (typeof slot.available !== 'boolean') return { error: 'slot.available 必须是布尔值' };
    if (toMinutes(slot.endTime) <= toMinutes(slot.startTime)) return { error: 'slot 结束时间必须晚于开始时间' };
    if (previousStart != null && toMinutes(slot.startTime) <= previousStart) {
      return { error: 'slots 必须按 startTime 升序且唯一' };
    }
    previousStart = toMinutes(slot.startTime);
    normalized.push({ startTime: slot.startTime, endTime: slot.endTime, available: slot.available });
  }
  return { normalized };
}

export function normalizeAvailabilityPayload(payload) {
  if (payload == null || typeof payload !== 'object' || Array.isArray(payload)) {
    throw new TypeError('可用时段载荷必须是对象');
  }
  const unknown = Object.keys(payload).filter((key) => !AVAILABILITY_KEYS.includes(key));
  if (unknown.length > 0) throw new TypeError(`可用时段载荷存在未知字段: ${unknown.join(', ')}`);
  if (!isResourceId(payload.resourceId)) throw new TypeError('resourceId 必须是非零十进制数字符串');
  if (!isValidDate(payload.date)) throw new TypeError('date 必须是 yyyy-MM-dd');
  if (payload.slotMinutes !== SLOT_MINUTES) throw new TypeError('slotMinutes 必须为 30');
  const slots = normalizeSlots(payload.slots);
  if (slots.error) throw new TypeError(slots.error);
  return { resourceId: payload.resourceId, date: payload.date, slotMinutes: payload.slotMinutes, slots: slots.normalized };
}

export function validateInterval({ startTime, endTime }) {
  const errors = [];
  if (!isValidDateTime(startTime)) errors.push('startTime 必须是 yyyy-MM-dd HH:mm:ss');
  if (!isValidDateTime(endTime)) errors.push('endTime 必须是 yyyy-MM-dd HH:mm:ss');
  if (errors.length > 0) return fail(...errors);
  const [startDate, startClock] = startTime.split(' ');
  const [endDate, endClock] = endTime.split(' ');
  if (startDate !== endDate) return fail('预约不允许跨日');
  for (const clock of [startClock, endClock]) {
    if (clock.slice(6) !== '00' || Number(clock.slice(3, 5)) % SLOT_MINUTES !== 0) {
      return fail('时间必须对齐半小时边界且秒为零');
    }
  }
  if (startTime >= endTime) return fail('startTime 必须早于 endTime');
  return ok({ startTime, endTime });
}

export function buildSelection(date, selected) {
  if (!Array.isArray(selected) || selected.length === 0) return fail('至少选择一个可用时段');
  for (const slot of selected) {
    if (slot == null || !isSlotTime(slot.startTime) || !isSlotTime(slot.endTime)) return fail('选择了无效时段');
    if (slot.available !== true) return fail('不可选择不可用或已过期时段');
  }
  for (let i = 1; i < selected.length; i += 1) {
    if (toMinutes(selected[i].startTime) <= toMinutes(selected[i - 1].startTime)) return fail('时段必须按升序选择');
    if (selected[i].startTime !== selected[i - 1].endTime) return fail('所选时段必须连续');
  }
  const first = selected[0];
  const last = selected[selected.length - 1];
  const durationMinutes = toMinutes(last.endTime) - toMinutes(first.startTime);
  const interval = validateInterval({
    startTime: `${date} ${first.startTime}:00`,
    endTime: `${date} ${last.endTime}:00`,
  });
  if (!interval.valid) return interval;
  return ok({
    date,
    startTime: interval.value.startTime,
    endTime: interval.value.endTime,
    slotCount: selected.length,
    durationMinutes,
  });
}

export function countCodePoints(value) {
  return codePoints(value);
}
