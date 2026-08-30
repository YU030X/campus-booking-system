import { unwrapResult } from './adminEnvelope.js';
import { http } from './http.js';
import {
  dateRange,
  mapBookingStatistics,
  mapNotificationPage,
  mapResourceStatistics,
  mapSupportError,
  requireId,
} from './supportCore.js';

export {
  mapBookingStatistics,
  mapNotificationPage,
  mapResourceStatistics,
  mapSupportError,
  requireDate,
} from './supportCore.js';

const call = (request, mapper = (value) => value) => request
  .then(unwrapResult)
  .then(mapper)
  .catch((error) => { throw mapSupportError(error); });

export const supportApi = {
  notifications: ({ pageNumber = 1, pageSize = 10 } = {}) => call(
    http.get('/notifications', { params: { pageNumber, pageSize } }),
    mapNotificationPage,
  ),
  markNotificationRead: (notificationId) => call(
    http.post(`/notifications/${encodeURIComponent(requireId(notificationId))}/read`),
  ),
  resourceStatistics: (fromDate, toDate) => call(
    http.get('/admin/statistics/resources', { params: dateRange(fromDate, toDate) }),
    mapResourceStatistics,
  ),
  bookingStatistics: (fromDate, toDate) => call(
    http.get('/admin/statistics/bookings', { params: dateRange(fromDate, toDate) }),
    mapBookingStatistics,
  ),
};
