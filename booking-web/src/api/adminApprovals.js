export const MAX_PAGE_SIZE = 100;
export const BOOKING_VIEW_FIELDS = [
  'id', 'bookingNo', 'userId', 'resourceId', 'startTime', 'endTime',
  'purpose', 'attendeeCount', 'status', 'checkinTime', 'cancelTime',
  'cancelReason', 'createdAt', 'updatedAt',
];
export const APPROVE_ACTION = 'approve';
export const REJECT_ACTION = 'reject';

const TIMESTAMP_PATTERN = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/;
const ID_PATTERN = /^\d+$/;
export const APPROVAL_STATUSES = [
  'PENDING_APPROVAL',
  'CONFIRMED',
  'CHECKED_IN',
  'COMPLETED',
  'REJECTED',
  'CANCELLED',
  'NO_SHOW',
];
const APPROVAL_STATUS_SET = new Set(APPROVAL_STATUSES);

const requireCanonicalTimestamp = (value, name, { nullable = false } = {}) => {
  if (nullable && (value === null || value === undefined)) return null;
  if (typeof value !== 'string' || !TIMESTAMP_PATTERN.test(value)) {
    throw new Error(`BookingView ${name} 必须是 yyyy-MM-dd HH:mm:ss 格式`);
  }
  return value;
};

const assertBookingId = (value, name = 'id') => {
  if (typeof value !== 'string' || !ID_PATTERN.test(value) || value === '0') {
    throw new TypeError(`${name} 必须是非零十进制字符串`);
  }
  return value;
};

export function validateBookingView(record) {
  if (!record || typeof record !== 'object' || Array.isArray(record)) {
    throw new Error('BookingView 记录格式无效');
  }
  const allowed = new Set(BOOKING_VIEW_FIELDS);
  for (const key of Object.keys(record)) {
    if (!allowed.has(key)) throw new Error(`BookingView 出现未知字段: ${key}`);
  }
  for (const key of BOOKING_VIEW_FIELDS) {
    if (!(key in record)) throw new Error(`BookingView 缺少字段: ${key}`);
  }
  assertBookingId(record.id);
  assertBookingId(record.userId, 'userId');
  assertBookingId(record.resourceId, 'resourceId');
  if (typeof record.bookingNo !== 'string' || record.bookingNo.length === 0) {
    throw new Error('BookingView bookingNo 非法');
  }
  if (typeof record.purpose !== 'string' && record.purpose !== null) {
    throw new Error('BookingView purpose 必须是字符串或 null');
  }
  if (!APPROVAL_STATUS_SET.has(record.status)) {
    throw new Error(`BookingView status 必须是 ${APPROVAL_STATUSES.join('/')} 之一`);
  }
  if (!Number.isInteger(record.attendeeCount) || record.attendeeCount < 1) {
    throw new Error('BookingView attendeeCount 必须是 >=1 的整数');
  }
  requireCanonicalTimestamp(record.startTime, 'startTime');
  requireCanonicalTimestamp(record.endTime, 'endTime');
  requireCanonicalTimestamp(record.createdAt, 'createdAt');
  requireCanonicalTimestamp(record.updatedAt, 'updatedAt');
  requireCanonicalTimestamp(record.checkinTime, 'checkinTime', { nullable: true });
  requireCanonicalTimestamp(record.cancelTime, 'cancelTime', { nullable: true });
  if (record.cancelReason !== null && typeof record.cancelReason !== 'string') {
    throw new Error('BookingView cancelReason 必须是字符串或 null');
  }
  return record;
}

export function canActOn(booking) {
  return !!booking && booking.status === 'PENDING_APPROVAL';
}

export function normalizeApproveComment(raw) {
  const comment = typeof raw === 'string' ? raw.trim() : '';
  if (!comment) return null;
  const codePoints = [...comment].length;
  if (codePoints > 500) {
    throw new RangeError('批准备注最多 500 个 Unicode 码点');
  }
  return comment;
}

export function validateRejectComment(raw) {
  const comment = typeof raw === 'string' ? raw.trim() : '';
  const codePoints = [...comment].length;
  if (codePoints < 1) {
    throw new RangeError('驳回备注必填');
  }
  if (codePoints > 500) {
    throw new RangeError('驳回备注最多 500 个 Unicode 码点');
  }
  return comment;
}

export const codePointLength = (value) => (typeof value === 'string' ? [...value].length : 0);

function requirePageInt(value, name, min, max = Number.MAX_SAFE_INTEGER) {
  const n = typeof value === 'number' ? value : Number(value);
  if (!Number.isInteger(n) || n < min || n > max) {
    throw new Error(`PageResult ${name} 非法`);
  }
  return n;
}

export function mapPendingPage(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error('PageResult 格式无效');
  if (!Array.isArray(data.records)) throw new Error('PageResult records 必须是数组');
  const records = data.records.map((item) => validateBookingView(item));
  return {
    records,
    pageNumber: requirePageInt(data.pageNumber, 'pageNumber', 1),
    pageSize: requirePageInt(data.pageSize, 'pageSize', 1, MAX_PAGE_SIZE),
    total: requirePageInt(data.total, 'total', 0),
  };
}

const ERROR_BY_CODE = {
  40000: '请求参数无效',
  40100: '登录已失效，请重新登录',
  40300: '无权限执行该管理操作',
  40400: '预约不存在',
  43000: '预约状态冲突（已被处理或状态非法）',
};
const ERROR_BY_STATUS = {
  400: '请求参数无效',
  401: '登录已失效',
  403: '无权限',
  404: '预约不存在',
  409: '操作冲突，请刷新后重试',
};

export function mapAdminApprovalError(error) {
  const status = error?.response?.status ?? error?.status ?? null;
  const code = error?.code ?? error?.response?.data?.code ?? null;
  error.adminMessage = ERROR_BY_CODE[code] || ERROR_BY_STATUS[status] || error.userMessage || error.message || '操作失败';
  return error;
}

function toPageNumber(value) {
  const n = Number(value);
  return Number.isInteger(n) && n >= 1 ? n : 1;
}

function clampPageSize(value) {
  let size = toPageNumber(Number(value));
  size = Number.isInteger(size) ? size : 10;
  return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
}

export function createAdminApprovalsCore(transport) {
  if (!transport || typeof transport.list !== 'function' || typeof transport.action !== 'function') {
    throw new TypeError('transport 需要提供 list/action');
  }
  const state = {
    pageNumber: 1,
    pageSize: 10,
    page: { phase: 'idle', records: [], total: 0, pageNumber: 1, pageSize: 10, error: null },
    selected: { phase: 'none', source: null, booking: null, error: null },
    actions: {},
    inflight: {},
    sequence: 0,
  };

  async function fetchList({ force = false } = {}) {
    const params = { pageNumber: state.pageNumber, pageSize: state.pageSize };
    const key = `list:${JSON.stringify(params)}`;
    if (!force) {
      const existing = state.inflight[key];
      if (existing) return existing;
    }
    const sequence = ++state.sequence;
    const task = Promise.resolve().then(async () => {
      state.page = { ...state.page, phase: 'loading', error: null };
      try {
        const result = mapPendingPage(await transport.list(params));
        if (sequence !== state.sequence) return result;
        state.page = {
          phase: result.records.length ? 'success' : 'empty',
          records: result.records,
          total: result.total,
          pageNumber: result.pageNumber,
          pageSize: result.pageSize,
          error: null,
        };
        if (state.selected.phase === 'ready' && state.selected.source === 'list') {
          const refreshed = result.records.find((row) => row.id === state.selected.booking.id);
          state.selected = refreshed
            ? { phase: 'ready', source: 'list', booking: refreshed, error: null }
            : { phase: 'none', source: null, booking: null, error: null };
        }
        return result;
      } catch (error) {
        if (sequence === state.sequence) {
          state.page = { ...state.page, phase: 'error', error: mapAdminApprovalError(error) };
        }
        throw error;
      } finally {
        delete state.inflight[key];
      }
    });
    state.inflight[key] = task;
    return task;
  }

  function setSelectedFrom(row) {
    try {
      state.selected = { phase: 'ready', source: 'list', booking: validateBookingView(row), error: null };
    } catch (error) {
      state.selected = { phase: 'error', source: 'list', booking: null, error };
    }
    return state.selected;
  }

  return {
    state,
    fetchList,
    setSelectedFrom,
    clearSelection() {
      state.selected = { phase: 'none', source: null, booking: null, error: null };
    },
    setPage(pageNumber) {
      state.pageNumber = toPageNumber(pageNumber);
      return fetchList();
    },
    setPageSize(pageSize) {
      state.pageSize = clampPageSize(pageSize);
      state.pageNumber = 1;
      return fetchList();
    },
    retry() {
      return fetchList({ force: true });
    },
    refreshTruth() {
      return fetchList({ force: true });
    },
    requestAction(id, action, comment) {
      const bookingId = assertBookingId(id);
      if (action !== APPROVE_ACTION && action !== REJECT_ACTION) {
        throw new TypeError('action 必须是 approve 或 reject');
      }
      const body = action === APPROVE_ACTION
        ? { comment: normalizeApproveComment(comment) }
        : { comment: validateRejectComment(comment) };
      const key = `approval:${bookingId}:${action}`;
      const existing = state.actions[bookingId];
      if (existing && existing.phase === 'loading' && existing.promise instanceof Promise) {
        if (existing.key !== key) {
          throw new TypeError('该预约已有进行中的审批操作，请等待完成');
        }
        return existing.promise;
      }
      let settle;
      const promise = new Promise((resolve, reject) => { settle = { resolve, reject }; });
      const run = async () => {
        state.actions[bookingId] = { key, phase: 'loading', error: null, adminMessage: null, promise };
        try {
          const booking = validateBookingView(await transport.action(bookingId, action, body));
          state.actions[bookingId] = { key, phase: 'success', error: null, adminMessage: null, promise: null };
          state.selected = { phase: 'ready', source: 'action', booking, error: null };
          settle.resolve(booking);
          try {
            await this.refreshTruth();
          } catch {
            /* truth refresh failure only surfaces through the page error state */
          }
        } catch (error) {
          state.actions[bookingId] = {
            key,
            phase: 'error',
            error,
            adminMessage: mapAdminApprovalError(error).adminMessage,
            promise: null,
          };
          try {
            await this.refreshTruth();
          } catch {
            /* truth refresh failure already surfaces through list phase */
          }
          settle.reject(error);
        }
      };
      run();
      return promise;
    },
  };
}

export async function openAdminApprovalsApi() {
  const [{ http }, { unwrapResult }] = await Promise.all([import('./http'), import('./adminEnvelope')]);
  const guarded = (error) => {
    throw mapAdminApprovalError(error);
  };
  return {
    list: (params) => http.get('/admin/approvals', { params }).then(unwrapResult).catch(guarded),
    action: (id, action, body) => http
      .post(`/admin/bookings/${encodeURIComponent(assertBookingId(id, 'booking id'))}/${action}`, body)
      .then(unwrapResult)
      .catch(guarded),
  };
}
