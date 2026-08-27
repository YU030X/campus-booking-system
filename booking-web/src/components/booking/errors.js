export const SLOT_CONFLICT_BACKEND_MESSAGE = '该时段已被占用，请刷新后重试';
export const SYSTEM_BUSY_BACKEND_MESSAGE = '当前预约请求较多，请稍后重试';

export const SLOT_CONFLICT_USER_MESSAGE = '该时段刚被其他人预约，请刷新';
export const SYSTEM_BUSY_USER_MESSAGE = '当前预约请求较多，请稍后重试';
export const CONFLICT_USER_MESSAGE = '预约提交冲突，请稍后重试';
export const AUTH_EXPIRED_USER_MESSAGE = '登录已失效，请重新登录';
export const FORBIDDEN_USER_MESSAGE = '无权限执行该操作';
export const NOT_FOUND_USER_MESSAGE = '记录不存在或已被删除';
export const INVALID_INPUT_USER_MESSAGE = '请求参数无效';
export const UNKNOWN_USER_MESSAGE = '请求失败，请重试';

export const KINDS = Object.freeze({
  SLOT_CONFLICT: 'SLOT_CONFLICT',
  SYSTEM_BUSY: 'SYSTEM_BUSY',
  CONFLICT: 'CONFLICT',
  AUTH_EXPIRED: 'AUTH_EXPIRED',
  FORBIDDEN: 'FORBIDDEN',
  NOT_FOUND: 'NOT_FOUND',
  INVALID_INPUT: 'INVALID_INPUT',
  UNKNOWN: 'UNKNOWN',
});

function extract(error) {
  const response = error?.response;
  const body = response?.data;
  return {
    status: typeof response?.status === 'number' ? response.status : (typeof error?.status === 'number' ? error.status : null),
    code: typeof body?.code === 'number' ? body.code : (typeof error?.code === 'number' ? error.code : null),
    message: typeof body?.message === 'string' ? body.message : (typeof error?.message === 'string' ? error.message : ''),
  };
}

export function classifyBookingError(error) {
  const { status, code, message } = extract(error);
  if (status === 409 && code === 43000) {
    if (message === SLOT_CONFLICT_BACKEND_MESSAGE) {
      return { kind: KINDS.SLOT_CONFLICT, status, code, message, userMessage: SLOT_CONFLICT_USER_MESSAGE, refreshSlots: true, retryable: false };
    }
    if (message === SYSTEM_BUSY_BACKEND_MESSAGE) {
      return { kind: KINDS.SYSTEM_BUSY, status, code, message, userMessage: SYSTEM_BUSY_USER_MESSAGE, refreshSlots: false, retryable: true };
    }
    return { kind: KINDS.CONFLICT, status, code, message, userMessage: CONFLICT_USER_MESSAGE, refreshSlots: false, retryable: false };
  }
  if (status === 401) {
    return { kind: KINDS.AUTH_EXPIRED, status, code, message, userMessage: AUTH_EXPIRED_USER_MESSAGE, refreshSlots: false, retryable: false, sessionAction: 'clear-via-shared-handler' };
  }
  if (status === 403) {
    return { kind: KINDS.FORBIDDEN, status, code, message, userMessage: FORBIDDEN_USER_MESSAGE, refreshSlots: false, retryable: false, sessionAction: null };
  }
  if (status === 404) {
    return { kind: KINDS.NOT_FOUND, status, code, message, userMessage: NOT_FOUND_USER_MESSAGE, refreshSlots: false, retryable: false };
  }
  if (status === 400 || code === 40000) {
    return { kind: KINDS.INVALID_INPUT, status, code, message, userMessage: INVALID_INPUT_USER_MESSAGE, refreshSlots: false, retryable: false };
  }
  return { kind: KINDS.UNKNOWN, status, code, message, userMessage: UNKNOWN_USER_MESSAGE, refreshSlots: false, retryable: true };
}

export function mapAndThrow(error) {
  throw Object.assign(classifyBookingError(error), { cause: error });
}
