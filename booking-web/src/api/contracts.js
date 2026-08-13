export const API_BASE = '/api/v1';
export const API = Object.freeze({
 auth: { register:'POST /auth/register', login:'POST /auth/login' },
 users: { me:'GET /users/me', updateMe:'PUT /users/me', adminList:'GET /admin/users', updateStatus:'PATCH /admin/users/{id}/status', violations:'GET /users/me/violations' },
 categories: { list:'GET /categories', create:'POST /admin/categories', update:'PUT /admin/categories/{id}', remove:'DELETE /admin/categories/{id}' },
 resources: { list:'GET /resources', detail:'GET /resources/{id}', slots:'GET /resources/{id}/available-slots', create:'POST /admin/resources', update:'PUT /admin/resources/{id}', status:'PATCH /admin/resources/{id}/status', rules:'PUT /admin/resources/{id}/time-rules', closures:'POST /admin/resources/{id}/closures', removeClosure:'DELETE /admin/resources/{id}/closures/{closureId}' },
 bookings: { create:'POST /bookings', list:'GET /bookings', detail:'GET /bookings/{id}', cancel:'POST /bookings/{id}/cancel', checkIn:'POST /bookings/{id}/check-in' },
 approvals: { list:'GET /admin/approvals', approve:'POST /admin/bookings/{id}/approve', reject:'POST /admin/bookings/{id}/reject' },
 support: { notifications:'GET /notifications', readNotification:'POST /notifications/{id}/read', resourceStats:'GET /admin/statistics/resources', bookingStats:'GET /admin/statistics/bookings' }
});
