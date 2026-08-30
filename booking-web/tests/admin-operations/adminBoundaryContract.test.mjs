import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { mapAdminApprovalError } from '../../src/api/adminApprovals.js';
import { mapAdminUserError } from '../../src/api/adminUsers.js';

const source = (relative) => readFileSync(
  fileURLToPath(new URL(`../../${relative}`, import.meta.url)),
  'utf8',
);

const approvalsView = source('src/views/admin/approvals/Index.vue');
const usersView = source('src/views/admin/users/Index.vue');
const routerSource = source('src/router/index.js');
const httpSource = source('src/api/http.js');

test('frozen admin routes remain ADMIN-only and point at the T11 views', () => {
  assert.match(routerSource, /path:\s*'\/admin\/approvals'[\s\S]*?component:\s*AdminApprovals[\s\S]*?roles:\s*\['ADMIN'\]/);
  assert.match(routerSource, /path:\s*'\/admin\/users'[\s\S]*?component:\s*AdminUsers[\s\S]*?roles:\s*\['ADMIN'\]/);
});

test('both views deny non-admin principals before their first list request', () => {
  for (const [name, view] of [['approvals', approvalsView], ['users', usersView]]) {
    assert.match(view, /v-if="!isAdmin"[\s\S]*?仅 ADMIN 可访问/);
    const guardAt = view.indexOf('if (!isAdmin.value) return;');
    const fetchAt = view.indexOf('store.fetchList()');
    assert.ok(guardAt >= 0, `${name} is missing its non-admin request gate`);
    assert.ok(fetchAt > guardAt, `${name} fetches before the non-admin gate`);
  }
});

test('admin operation confirmations are in-layout and never use native popups', () => {
  const combined = `${approvalsView}\n${usersView}`;
  assert.doesNotMatch(combined, /\b(?:window\.)?(?:alert|confirm|prompt)\s*\(/);
  assert.match(approvalsView, /<ApprovalCommentDialog/);
  assert.match(usersView, /<UserStatusDialog/);
});

test('shared auth boundary clears only on 401 and preserves the session on 403', () => {
  assert.match(httpSource, /status === 401[\s\S]*?auth401Promise/);
  assert.match(httpSource, /status === 403\) on403/);
  assert.match(routerSource, /on401:[\s\S]*?store\.clear\(\)[\s\S]*?path:\s*'\/login'/);
  assert.match(routerSource, /on403:\s*\(\)\s*=>\s*useAuthStore\(\)\.setForbidden\(true\)/);
  const on403 = routerSource.match(/on403:[^\n]+/)?.[0] || '';
  assert.doesNotMatch(on403, /clear|logout/);
});

test('approval and user 401/403/404/409 errors remain actionable without session mutation', () => {
  const approvalCases = [[401, 40100], [403, 40300], [404, 40400], [409, 43000]];
  const userCases = [[401, 40100], [403, 40300], [404, 40400], [409, 41000]];
  for (const [status, code] of approvalCases) {
    const error = Object.assign(new Error('transport failure'), { response: { status, data: { code } } });
    assert.strictEqual(mapAdminApprovalError(error), error);
    assert.ok(error.adminMessage, `approval ${status}/${code} lacks actionable text`);
  }
  for (const [status, code] of userCases) {
    const error = Object.assign(new Error('transport failure'), { response: { status, data: { code } } });
    assert.strictEqual(mapAdminUserError(error), error);
    assert.ok(error.adminMessage, `user ${status}/${code} lacks actionable text`);
  }
  assert.doesNotMatch(`${mapAdminApprovalError}\n${mapAdminUserError}`, /sessionStorage|logout|clear\(/);
});
