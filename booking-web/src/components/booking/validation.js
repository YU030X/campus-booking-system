import { BOOKING_STATUS } from '../../types/contracts.js';

export const PURPOSE_MAX_CODE_POINTS = 500;
export const CANCEL_REASON_MAX_CODE_POINTS = 200;

const RESOURCE_ID_RE = /^[1-9]\d*$/;
const BOOKING_ID_RE = /^[1-9]\d*$/;
const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;
const DATETIME_RE = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})$/;

const CREATE_KEYS = ['resourceId', 'startTime', 'endTime', 'purpose', 'attendeeCount'];
const VIEW_KEYS = [
  'id', 'bookingNo', 'userId', 'resourceId', 'startTime', 'endTime',
  'purpose', 'attendeeCount', 'status', 'checkinTime', 'cancelTime',
  'cancelReason', 'createdAt', 'updatedAt',
];
const VIEW_DATETIMES = ['startTime', 'endTime', 'createdAt', 'updatedAt'];
const VIEW_NULLABLE_DATETIMES = ['checkinTime', 'cancelTime'];
const VIEW_NULLABLE_STRINGS = ['purpose', 'cancelReason'];

export const ok = (value) => ({ valid: true, errors: [], value });
export const fail = (...errors) => ({ valid: false, errors, value: null });

export const codePoints = (value) => [...value].length;
export const isResourceId = (value) => typeof value === 'string' && RESOURCE_ID_RE.test(value);
export const isBookingId = (value) => typeof value === 'string' && BOOKING_ID_RE.test(value);

export function isValidDate(value) {
  if (typeof value !== 'string') return false;
  const m = DATE_RE.exec(value);
  if (!m) return false;
  const year = Number(m[1]);
  const month = Number(m[2]);
  const day = Number(m[3]);
  if (month < 1 || month > 12 || day < 1) return false;
  const daysInMonth = new Date(Date.UTC(year, month, 0)).getUTCDate();
  return day <= daysInMonth;
}

export function isValidDateTime(value) {
  if (typeof value !== 'string') return false;
  const m = DATETIME_RE.exec(value);
  if (!m) return false;
  if (!isValidDate(`${m[1]}-${m[2]}-${m[3]}`)) return false;
  const hours = Number(m[4]);
  const minutes = Number(m[5]);
  const seconds = Number(m[6]);
  return hours <= 23 && minutes <= 59 && seconds <= 59;
}

export function normalizePurpose(value) {
  if (value == null) return { error: null, value: null };
  if (typeof value !== 'string') return { error: 'purpose 必须是字符串或 null', value: null };
  const trimmed = value.trim();
  if (trimmed === '') return { error: null, value: null };
  if (codePoints(trimmed) > PURPOSE_MAX_CODE_POINTS) {
    return { error: `purpose 不能超过 ${PURPOSE_MAX_CODE_POINTS} 个 Unicode 码点`, value: null };
  }
  return { error: null, value: trimmed };
}

export function normalizeCancelReason(value) {
  if (value == null) return { error: null, value: null };
  if (typeof value !== 'string') return { error: 'cancelReason 必须是字符串或 null', value: null };
  const trimmed = value.trim();
  if (trimmed === '') return { error: null, value: null };
  if (codePoints(trimmed) > CANCEL_REASON_MAX_CODE_POINTS) {
    return { error: `cancelReason 不能超过 ${CANCEL_REASON_MAX_CODE_POINTS} 个 Unicode 码点`, value: null };
  }
  return { error: null, value: trimmed };
}

export function validateCreateInput(input) {
  if (input == null || typeof input !== 'object' || Array.isArray(input)) {
    return fail('创建预约请求必须是对象');
  }
  const unknown = Object.keys(input).filter((key) => !CREATE_KEYS.includes(key));
  if (unknown.length > 0) return fail(`未知字段: ${unknown.join(', ')}`);
  const errors = [];
  if (!isResourceId(input.resourceId)) errors.push('resourceId 必须是非零十进制数字符串');
  if (!isValidDateTime(input.startTime)) errors.push('startTime 必须是 yyyy-MM-dd HH:mm:ss');
  if (!isValidDateTime(input.endTime)) errors.push('endTime 必须是 yyyy-MM-dd HH:mm:ss');
  const purpose = normalizePurpose(input.purpose);
  if (purpose.error) errors.push(purpose.error);
  if (!Number.isInteger(input.attendeeCount) || input.attendeeCount < 1) {
    errors.push('attendeeCount 必须是不小于 1 的整数');
  }
  if (errors.length > 0) return fail(...errors);
  return ok({
    resourceId: input.resourceId,
    startTime: input.startTime,
    endTime: input.endTime,
    purpose: purpose.value,
    attendeeCount: input.attendeeCount,
  });
}

export function parseBookingHandoff(query) {
  if (query == null || typeof query !== 'object' || Array.isArray(query)) return null;
  const keys = Object.keys(query);
  if (keys.length !== 2 || keys.some((key) => !['resourceId', 'date'].includes(key))) return null;
  if (!isResourceId(query.resourceId) || !isValidDate(query.date)) return null;
  return { resourceId: query.resourceId, date: query.date };
}

export function validatePageQuery(query) {
  const source = query == null ? {} : query;
  if (typeof source !== 'object' || Array.isArray(source)) return fail('分页查询必须是对象');
  const pageNumber = source.pageNumber == null ? 1 : source.pageNumber;
  const pageSize = source.pageSize == null ? 10 : source.pageSize;
  const status = source.status == null || source.status === '' ? null : source.status;
  const errors = [];
  if (!Number.isInteger(pageNumber) || pageNumber < 1) errors.push('pageNumber 必须是不小于 1 的整数');
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 100) errors.push('pageSize 必须在 1..100 之间');
  if (status != null && !BOOKING_STATUS.includes(status)) errors.push('status 必须是七状态枚举之一');
  if (errors.length > 0) return fail(...errors);
  return ok({ pageNumber, pageSize, status });
}

export function normalizeBookingView(raw) {
  if (raw == null || typeof raw !== 'object' || Array.isArray(raw)) return fail('BookingView 必须是对象');
  const unknown = Object.keys(raw).filter((key) => !VIEW_KEYS.includes(key));
  if (unknown.length > 0) return fail(`BookingView 存在未知字段: ${unknown.join(', ')}`);
  const errors = [];
  for (const key of ['id', 'userId', 'resourceId']) {
    if (!isBookingId(raw[key])) errors.push(`BookingView.${key} 必须是非零十进制数字符串`);
  }
  if (typeof raw.bookingNo !== 'string' || raw.bookingNo === '') errors.push('BookingView.bookingNo 必须是非空字符串');
  for (const key of VIEW_DATETIMES) {
    if (!isValidDateTime(raw[key])) errors.push(`BookingView.${key} 必须是 yyyy-MM-dd HH:mm:ss`);
  }
  for (const key of VIEW_NULLABLE_DATETIMES) {
    if (raw[key] != null && !isValidDateTime(raw[key])) errors.push(`BookingView.${key} 必须是 yyyy-MM-dd HH:mm:ss 或 null`);
  }
  for (const key of VIEW_NULLABLE_STRINGS) {
    if (raw[key] != null && typeof raw[key] !== 'string') errors.push(`BookingView.${key} 必须是字符串或 null`);
  }
  if (!Number.isInteger(raw.attendeeCount) || raw.attendeeCount < 1) errors.push('BookingView.attendeeCount 必须是不小于 1 的整数');
  if (!BOOKING_STATUS.includes(raw.status)) errors.push('BookingView.status 必须是七状态枚举之一');
  if (errors.length > 0) return fail(...errors);
  return ok(Object.fromEntries(VIEW_KEYS.map((key) => [key, raw[key] === undefined ? null : raw[key]])));
}

export function normalizeBookingPage(raw) {
  if (raw == null || typeof raw !== 'object') return fail('分页结果必须是对象');
  const errors = [];
  const { pageNumber, pageSize, total, records } = raw;
  if (!Number.isInteger(pageNumber) || pageNumber < 1) errors.push('pageNumber 非法');
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 100) errors.push('pageSize 非法');
  if (!Number.isInteger(total) || total < 0) errors.push('total 非法');
  if (!Array.isArray(records)) errors.push('records 必须是数组');
  if (errors.length > 0) return fail(...errors);
  const views = [];
  for (const record of records) {
    const view = normalizeBookingView(record);
    if (!view.valid) return fail(...view.errors.map((e) => `records: ${e}`));
    views.push(view.value);
  }
  return ok({ pageNumber, pageSize, total, records: views });
}
