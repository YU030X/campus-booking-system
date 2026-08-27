import {
  isBookingId,
  isResourceId,
  isValidDate,
  normalizeBookingPage,
  normalizeBookingView,
  normalizeCancelReason,
  validateCreateInput,
  validatePageQuery,
} from '../components/booking/validation.js';
import { normalizeAvailabilityPayload, validateInterval } from '../components/booking/slots.js';

export function createBookingApi(transport) {
  const encodeId = (value, label, validate) => {
    if (!validate(value)) throw new TypeError(`${label} 必须是非零十进制数字符串`);
    return encodeURIComponent(value);
  };

  const unwrap = (response) => {
    const body = response?.data;
    if (body == null || typeof body !== 'object' || body.code !== 0) {
      const error = new Error(body?.message || '请求失败');
      error.response = response;
      error.status = response?.status ?? null;
      error.code = typeof body?.code === 'number' ? body.code : null;
      throw error;
    }
    return body.data;
  };

  const request = (promise) => promise.then(unwrap);

  const view = (data) => {
    const normalized = normalizeBookingView(data);
    if (!normalized.valid) throw new TypeError(`契约漂移: ${normalized.errors.join('; ')}`);
    return normalized.value;
  };

  return {
    availability(resourceId, date) {
      encodeId(resourceId, 'resourceId', isResourceId);
      if (!isValidDate(date)) throw new TypeError('date 必须是 yyyy-MM-dd');
      return request(
        transport.get(`/resources/${encodeURIComponent(resourceId)}/available-slots`, { params: { date } }),
      ).then((payload) => normalizeAvailabilityPayload(payload));
    },

    create(input) {
      const validated = validateCreateInput(input);
      if (!validated.valid) {
        const error = new TypeError(validated.errors.join('; '));
        error.details = validated.errors;
        throw error;
      }
      const interval = validateInterval(validated.value);
      if (!interval.valid) {
        const error = new TypeError(interval.errors.join('; '));
        error.details = interval.errors;
        throw error;
      }
      return request(transport.post('/bookings', validated.value)).then(view);
    },

    list(query) {
      const validated = validatePageQuery(query);
      if (!validated.valid) {
        const error = new TypeError(validated.errors.join('; '));
        error.details = validated.errors;
        throw error;
      }
      const params = { pageNumber: validated.value.pageNumber, pageSize: validated.value.pageSize };
      if (validated.value.status != null) params.status = validated.value.status;
      return request(transport.get('/bookings', { params })).then((page) => {
        const normalized = normalizeBookingPage(page);
        if (!normalized.valid) throw new TypeError(`契约漂移: ${normalized.errors.join('; ')}`);
        return normalized.value;
      });
    },

    detail(bookingId) {
      const id = encodeId(bookingId, 'bookingId', isBookingId);
      return request(transport.get(`/bookings/${id}`)).then(view);
    },

    cancel(bookingId, cancelReason) {
      const id = encodeId(bookingId, 'bookingId', isBookingId);
      const reason = normalizeCancelReason(cancelReason);
      if (reason.error) throw new TypeError(reason.error);
      const body = reason.value == null ? {} : { cancelReason: reason.value };
      return request(transport.post(`/bookings/${id}/cancel`, body)).then(view);
    },
  };
}

let sharedApiPromise = null;

export function getBookingApi() {
  if (sharedApiPromise == null) {
    sharedApiPromise = import('./http.js').then(({ http }) => createBookingApi(http));
  }
  return sharedApiPromise;
}
