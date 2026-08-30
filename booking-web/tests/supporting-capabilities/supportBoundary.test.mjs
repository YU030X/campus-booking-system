import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const source = (relative) => readFileSync(
  fileURLToPath(new URL(`../../${relative}`, import.meta.url)),
  'utf8',
);

const router = source('src/router/index.js');
const layout = source('src/layouts/AuthenticatedLayout.vue');
const api = source('src/api/support.js');
const notifications = source('src/views/notifications/Index.vue');
const statistics = source('src/views/admin/statistics/Index.vue');

test('shared owner routes expose notifications to authenticated roles and statistics to ADMIN only', () => {
  assert.match(router, /path:\s*'\/notifications'[\s\S]*?component:\s*Notifications[\s\S]*?roles:\s*studentRoles/);
  assert.match(router, /path:\s*'\/admin\/statistics'[\s\S]*?component:\s*AdminStatistics[\s\S]*?roles:\s*\['ADMIN'\]/);
  assert.match(layout, /to="\/notifications"/);
  assert.match(layout, /v-if="isAdmin"[\s\S]*?to="\/admin\/statistics"/);
});

test('support transport uses only the frozen backend paths', () => {
  for (const path of [
    "http.get('/notifications'",
    "http.post(`/notifications/${encodeURIComponent(requireId(notificationId))}/read`",
    "http.get('/admin/statistics/resources'",
    "http.get('/admin/statistics/bookings'",
  ]) assert.ok(api.includes(path), `missing ${path}`);
});

test('views include loading error empty and ownership states without native popups', () => {
  assert.match(notifications, /正在加载通知/);
  assert.match(notifications, /暂无通知/);
  assert.match(notifications, /markNotificationRead/);
  assert.match(statistics, /仅 ADMIN 可访问统计/);
  assert.match(statistics, /统计范围最多 366 天/);
  assert.match(statistics, /usageRate/);
  assert.doesNotMatch(`${notifications}\n${statistics}`, /\b(?:window\.)?(?:alert|confirm|prompt)\s*\(/);
});
