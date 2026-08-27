/**
 * Canonical Result envelope unwrap shared by T11 admin modules.
 * Non-zero business codes surface as errors while keeping the
 * original response and envelope fields for diagnostics.
 */
export function unwrapResult(response) {
  const body = response?.data;

  if (
    !body
    || typeof body !== 'object'
    || Array.isArray(body)
    || typeof body.code !== 'number'
    || typeof body.message !== 'string'
  ) {
    const malformed = new Error('响应缺少 canonical Result 信封');
    malformed.response = response;
    malformed.code = null;
    throw malformed;
  }

  if (body.code !== 0) {
    const error = new Error(body.message || '请求失败');
    error.response = response;
    error.code = body.code ?? null;
    error.data = body.data ?? null;
    throw error;
  }

  return body.data;
}
