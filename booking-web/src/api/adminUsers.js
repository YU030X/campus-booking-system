export const MAX_PAGE_SIZE = 100;
export const USER_ROLES = ['STUDENT', 'ADMIN'];
export const USER_VIEW_FIELDS = ['id', 'username', 'realName', 'studentNo', 'phone', 'email', 'avatar', 'role', 'creditScore', 'status', 'createdAt', 'updatedAt'];

const toPositiveInt = (value, fallback) => {
  const n = Number(value);
  return Number.isInteger(n) && n >= 1 ? n : fallback;
};

const normalizeStatusValue = (value) => {
  if (value === 0 || value === 1) return value;
  if (value === '0' || value === '1') return Number(value);
  return '';
};

export function normalizeUsersQuery(input = {}) {
  const pageNumber = toPositiveInt(input.pageNumber, 1);
  let pageSize = toPositiveInt(input.pageSize, 10);
  if (pageSize > MAX_PAGE_SIZE) pageSize = MAX_PAGE_SIZE;
  const keyword = typeof input.keyword === 'string' ? input.keyword.trim() : '';
  const role = USER_ROLES.includes(input.role) ? input.role : '';
  const status = normalizeStatusValue(input.status);
  const params = { pageNumber, pageSize };
  if (keyword) params.keyword = keyword;
  if (role) params.role = role;
  if (status !== '') params.status = status;
  return params;
}

export function requestQueryKey(params) {
  return `list:${JSON.stringify(params)}`;
}

export function assertUserId(id) {
  if (typeof id !== 'string' || !/^\d+$/.test(id) || id === '0') {
    throw new TypeError('用户 ID 必须是非零十进制字符串');
  }
  return id;
}

export function normalizeTargetStatus(value) {
  if (value !== 0 && value !== 1 && value !== '0' && value !== '1') {
    throw new TypeError('目标状态必须是数字 0 或 1');
  }
  return Number(value);
}

const TIMESTAMP_PATTERN = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/;

function requireCanonicalTimestamp(value, name) {
  if (typeof value !== 'string' || !TIMESTAMP_PATTERN.test(value)) {
    throw new Error(`UserView ${name} 必须是 yyyy-MM-dd HH:mm:ss 格式`);
  }
  return value;
}

export function validateUserView(record) {
  if (!record || typeof record !== 'object' || Array.isArray(record)) {
    throw new Error('UserView 记录格式无效');
  }
  const allowed = new Set(USER_VIEW_FIELDS);
  for (const key of Object.keys(record)) {
    if (!allowed.has(key)) throw new Error(`UserView 出现未知字段: ${key}`);
  }
  for (const key of USER_VIEW_FIELDS) {
    if (!(key in record)) throw new Error(`UserView 缺少字段: ${key}`);
  }
  assertUserId(record.id);
  if (!USER_ROLES.includes(record.role)) throw new Error('UserView role 取值非法');
  if (![0, 1].includes(record.status)) throw new Error('UserView status 取值非法');
  if (!Number.isInteger(record.creditScore)) throw new Error('UserView creditScore 必须是整数');
  requireCanonicalTimestamp(record.createdAt, 'createdAt');
  requireCanonicalTimestamp(record.updatedAt, 'updatedAt');
  return record;
}

function requirePageInt(value, name, min, max = Number.MAX_SAFE_INTEGER) {
  const n = typeof value === 'number' ? value : Number(value);
  if (!Number.isInteger(n) || n < min || n > max) {
    throw new Error(`PageResult ${name} 非法`);
  }
  return n;
}

export function mapUserPage(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error('PageResult 格式无效');
  if (!Array.isArray(data.records)) throw new Error('PageResult records 必须是数组');
  const records = data.records.map((item) => validateUserView(item));
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
  40400: '用户不存在',
  41000: '状态更新被拒绝（不可禁用自己或目标状态冲突）',
};
const ERROR_BY_STATUS = {
  400: '请求参数无效',
  401: '登录已失效',
  403: '无权限',
  404: '用户不存在',
  409: '操作冲突，请刷新后重试',
};

export function mapAdminUserError(error) {
  const status = error?.response?.status ?? error?.status ?? null;
  const code = error?.code ?? error?.response?.data?.code ?? null;
  error.adminMessage = ERROR_BY_CODE[code] || ERROR_BY_STATUS[status] || error.userMessage || error.message || '操作失败';
  return error;
}

export function createAdminUsersCore(transport) {
  if (!transport || typeof transport.list !== 'function' || typeof transport.updateStatus !== 'function') {
    throw new TypeError('transport 需要提供 list/updateStatus');
  }
  const state = {
    filters: { keyword: '', role: '', status: '' },
    pageNumber: 1,
    pageSize: 10,
    page: { phase: 'idle', records: [], total: 0, pageNumber: 1, pageSize: 10, error: null },
    statusOps: {},
    inflight: {},
    sequence: 0,
  };

  const requestQuery = () => normalizeUsersQuery({
    pageNumber: state.pageNumber,
    pageSize: state.pageSize,
    keyword: state.filters.keyword,
    role: state.filters.role,
    status: state.filters.status,
  });

  async function fetchList({ force = false } = {}) {
    const params = requestQuery();
    const key = requestQueryKey(params);
    if (!force) {
      const existing = state.inflight[key];
      if (existing) return existing;
    }
    const sequence = ++state.sequence;
    const task = Promise.resolve().then(async () => {
      state.page = { ...state.page, phase: 'loading', error: null };
      try {
        const result = mapUserPage(await transport.list(params));
        if (sequence !== state.sequence) return result;
        state.page = {
          phase: result.records.length ? 'success' : 'empty',
          records: result.records,
          total: result.total,
          pageNumber: result.pageNumber,
          pageSize: result.pageSize,
          error: null,
        };
        return result;
      } catch (error) {
        if (sequence === state.sequence) {
          state.page = { ...state.page, phase: 'error', error: mapAdminUserError(error) };
        }
        throw error;
      } finally {
        delete state.inflight[key];
      }
    });
    state.inflight[key] = task;
    return task;
  }

  return {
    state,
    requestQuery,
    fetchList,
    applyFilters(patch = {}) {
      if ('keyword' in patch) state.filters.keyword = typeof patch.keyword === 'string' ? patch.keyword.trim() : '';
      if ('role' in patch) state.filters.role = patch.role;
      if ('status' in patch) state.filters.status = patch.status;
      state.pageNumber = 1;
      return fetchList();
    },
    setPage(pageNumber) {
      state.pageNumber = toPositiveInt(pageNumber, 1);
      return fetchList();
    },
    setPageSize(pageSize) {
      let size = toPositiveInt(pageSize, 10);
      if (size > MAX_PAGE_SIZE) size = MAX_PAGE_SIZE;
      state.pageSize = size;
      state.pageNumber = 1;
      return fetchList();
    },
    retry() {
      return fetchList({ force: true });
    },
    refreshTruth() {
      return fetchList({ force: true });
    },
    changeStatus(id, target) {
      const userId = assertUserId(id);
      const status = normalizeTargetStatus(target);
      const existing = state.statusOps[userId];
      if (existing && existing.phase === 'loading' && existing.promise instanceof Promise) return existing.promise;
      let resolveOp;
      const promise = new Promise((resolve, reject) => { resolveOp = { resolve, reject }; });
      const run = async () => {
        state.statusOps[userId] = { phase: 'loading', error: null, adminMessage: null, promise };
        try {
          const user = validateUserView(await transport.updateStatus(userId, status));
          const index = state.page.records.findIndex((row) => row.id === userId);
          if (index >= 0) {
            const records = state.page.records.slice();
            records.splice(index, 1, user);
            state.page = { ...state.page, records };
          }
          state.statusOps[userId] = { phase: 'success', error: null, adminMessage: null, promise: null };
          resolveOp.resolve(user);
          try {
            await this.refreshTruth();
          } catch {
            /* truth refresh failure only surfaces through the page error state */
          }
        } catch (error) {
          state.statusOps[userId] = { phase: 'error', error, adminMessage: mapAdminUserError(error).adminMessage, promise: null };
          try {
            await this.refreshTruth();
          } catch {
            /* truth refresh failure already surfaces through list phase */
          }
          resolveOp.reject(error);
        }
      };
      run();
      return promise;
    },
  };
}

export async function openAdminUsersApi() {
  const [{ http }, { unwrap }] = await Promise.all([import('./http'), import('./resourceCatalog')]);
  const guarded = (error) => {
    throw mapAdminUserError(error);
  };
  return {
    list: (params) => http.get('/admin/users', { params }).then(unwrap).catch(guarded),
    updateStatus: (id, status) => http
      .patch(`/admin/users/${encodeURIComponent(assertUserId(id))}/status`, { status: normalizeTargetStatus(status) })
      .then(unwrap)
      .catch(guarded),
  };
}
