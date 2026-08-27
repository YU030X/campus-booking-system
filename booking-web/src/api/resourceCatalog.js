import { http } from './http';

/**
 * Unwrap the canonical Result envelope returned by the resource API.
 * A non-zero business code is surfaced as an error while retaining the
 * original response and envelope fields for callers that need diagnostics.
 */
export function unwrap(response) {
  const body = response?.data;

  if (!body || body.code !== 0) {
    const error = new Error(body?.message || '请求失败');
    error.response = response;
    error.code = body?.code ?? null;
    error.data = body?.data ?? null;
    throw error;
  }

  return body.data;
}

/** Attach a stable, user-facing message without replacing the Axios error. */
export function mapResourceError(error) {
  const status = error?.response?.status;
  const code = error?.code ?? error?.response?.data?.code;
  const byCode = {
    40000: '请求参数无效',
    40100: '登录已失效',
    40300: '无权限',
    40400: '资源不存在',
    42000: '数据冲突',
  };
  const byStatus = {
    400: '请求参数无效',
    401: '登录已失效',
    403: '无权限',
    404: '资源不存在',
    409: '数据冲突',
  };

  error.userMessage = byCode[code] || byStatus[status] || error.message || '请求失败';
  return error;
}

const call = (request) => request.then(unwrap).catch((error) => {
  throw mapResourceError(error);
});

/** IDs are deliberately strict to prevent accidental path coercion. */
function encodeId(value, { allowZero = false } = {}) {
  if (typeof value !== 'string' || !/^\d+$/.test(value) || (!allowZero && value === '0')) {
    throw new TypeError('ID 必须是非空十进制字符串');
  }

  return encodeURIComponent(value);
}

export const resourceApi = {
  list: (params = {}) => call(http.get('/resources', { params })),

  detail: (resourceId) => call(
    http.get(`/resources/${encodeId(resourceId)}`),
  ),

  categories: () => call(http.get('/categories')),

  createCategory: (body) => call(http.post('/admin/categories', body)),

  updateCategory: (categoryId, body) => call(
    http.put(`/admin/categories/${encodeId(categoryId)}`, body),
  ),

  deleteCategory: (categoryId) => call(
    http.delete(`/admin/categories/${encodeId(categoryId)}`),
  ),

  create: (body) => call(http.post('/admin/resources', body)),

  update: (resourceId, body) => call(
    http.put(`/admin/resources/${encodeId(resourceId)}`, body),
  ),

  status: (resourceId, status) => call(
    http.patch(
      `/admin/resources/${encodeId(resourceId)}/status`,
      null,
      { params: { status } },
    ),
  ),

  replaceRules: (resourceId, rules) => call(
    http.put(`/admin/resources/${encodeId(resourceId)}/time-rules`, rules),
  ),

  addClosure: (resourceId, body) => call(
    http.post(`/admin/resources/${encodeId(resourceId, { allowZero: true })}/closures`, body),
  ),

  deleteClosure: (resourceId, closureId) => call(
    http.delete(
      `/admin/resources/${encodeId(resourceId, { allowZero: true })}/closures/${encodeId(closureId)}`,
    ),
  ),
};
