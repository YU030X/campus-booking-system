## Context

The shared contract freezes the twelve frontend routes, canonical envelopes/pagination, role and booking vocabularies, Long-as-string serialization, and `VITE_API_MODE=mock|real`. T04 owns the router, HTTP client, auth store, layouts, and route-component handoff; its sibling planning artifacts exist but remain unmerged, so the router/HTTP single-writer handoff gate remains. T08 owns student booking views; T05 owns resource-admin views. T02's identity contract defines the exact `UserView` and user-admin API, but T04 still has a post-T02 merge/rebase/browser gate. T09 approval planning artifacts and worktree exist in the sibling worktree but remain planning/unmerged; this design records only the reviewed contract and requires the post-merge/rebase handoff before apply.

## Goals / Non-Goals

**Goals:**

- Add isolated approval and user-operation view boundaries under the two existing admin route components.
- Keep server responses authoritative for status, pagination, idempotency, and conflict recovery.
- Make role denial, no-request behavior, validation, confirmation, de-duplication, and refetches testable with pure fixtures.
- Produce a deterministic path to real headless-browser screenshots and network evidence after backend gates.

**Non-Goals:**

- No router, Axios instance, auth store, layout, shared contract, package manifest, backend, SQL, resource-admin, or student-booking edits.
- No global mock server or invented DTO/field aliases.
- No client-side state machine, arbitrary target-status endpoint, popup confirmation, or direct component-to-Axios calls.
- No implementation, commit, push, or spec sync in this planning change.

## Decisions

### 1. Keep T04 as the route and authorization handoff owner

The change consumes the exact `/admin/approvals` and `/admin/users` route entries and receives the authenticated principal/forbidden layout from T04. View-level code may render an in-layout forbidden state and avoid fetching when the principal is not ADMIN, but it must not edit `router/index.js`, duplicate session cleanup, or create a catch-all route. This preserves one guard and one 401/403 policy. A local route guard or duplicated HTTP policy was rejected because it would drift from T04 and could issue requests before role denial.

### 2. Use one scoped API/store boundary per admin capability

Approval list/detail/action calls and user list/status calls are exposed through admin-operations API/store modules owned by this change; components consume those stores rather than calling Axios directly. Each store tracks request keys and an in-flight action key (`approval:{id}:{action}` or `user-status:{id}`) so repeated clicks are ignored until completion. This is preferred over component-local booleans because list/detail refreshes and errors need one consistent source of transient state. The modules select the already-frozen mock/real HTTP mode and add no new adapter infrastructure.

### 3. Treat approval DTOs as a hard T09 gate

Before real approval integration, record the actual T09 merge/rebase commit, then reread that exact handoff (delta/spec/design/tasks and backend contract) and record the list item, detail, action body, response, error, and stable-order fields. Until that pre-apply gate is recorded, pure fixtures may use only a clearly named shape mirroring the reviewed rules; production mapping must not guess names. The reviewed rules are: `GET /api/v1/admin/approvals?pageNumber&pageSize`, pending-only non-deleted records ordered `createdAt ASC,id ASC` with page size max 100; approve/reject at `POST /api/v1/admin/bookings/{id}/approve` and `/reject`; approve comment trim with blank→`null`, max 500 Unicode code points; reject comment trim to 1..500 Unicode code points; `BookingView`/`ApprovalView` Long IDs as strings; repeated identical actions return 200 with no side effects; opposite or illegal actions return 409/code `43000`; and every admin endpoint requires ADMIN, returning 403/code `40300` otherwise. These are reviewed planning inputs, not a claim that T09 is merged.

### 4. Normalize only at the boundary, never infer domain state

Query controls normalize page bounds, keyword trim, role/status allow-lists, and reject-comment validation before a request. Response mapping preserves IDs as strings, timestamps/statuses verbatim, and the server's stable order. Approve/reject controls are derived only from a returned `PENDING_APPROVAL` value; after every success or actionable failure the store refetches list/detail instead of applying a guessed transition. This is preferred over optimistic local status updates because 409 idempotency/illegal-state responses and concurrent administrators must remain visible.

### 5. Two-step confirmation is an in-layout flow

The first step opens an inline confirmation region/dialog owned by the view; the second explicit action sends the request. It uses no browser popup, and the confirm control shares the store's in-flight key. On cancel, form input remains untouched; on failure, useful unsent input remains available while server truth is refreshed. A one-click action was rejected because approval/rejection is an irreversible administrator operation.

### 6. Test pure fixtures first, then run gated real-browser evidence

Unit tests use local pure fixtures and request spies scoped to the admin modules; no global mocks are introduced. Required tests cover role guard/no request, confirmation/de-duplication, reject validation, success/failure refetch, user pagination/filter/status/self-disable, and loading/empty/error/retry. Real headless-browser checks run only after T04 and T09 gates plus backend availability, and capture screenshots and network evidence for both admin and student denial paths without popup dialogs.

## Risks / Trade-offs

- [Risk] T09 planning handoff changes before its merge/rebase or action DTO fields drift → Mitigation: record the actual merge/rebase commit before apply, reread authoritative artifacts, and block real approval integration on mismatch.
- [Risk] T04 route/auth handoff drifts before its merge/rebase → Mitigation: do not edit shared files; retain the router/HTTP single-writer gate, validate resolved route/forbidden/401 behavior in T04's handoff, and stop on mismatch.
- [Risk] A stale admin tab races another administrator → Mitigation: disable duplicate in-flight actions, treat 409 as actionable, and refetch server truth after both success and failure.
- [Risk] User filters contain stale/unknown values → Mitigation: allow-list and omit/normalize unknown values, preserving a usable unfiltered view.
- [Risk] Large pages or repeated refreshes create excessive requests → Mitigation: cap pageSize at 100, de-duplicate identical list/detail requests, and keep loading state scoped to the request key.
- [Risk] Fixture-only confidence misses integration wiring → Mitigation: make real browser/network/screenshots a post-backend acceptance gate and keep the PR Draft until evidence exists.

## Migration Plan

1. Review this change against main shared/identity specs and the T04/T08 sibling planning artifacts.
2. Before apply, record the actual T09 merge/rebase commit, reread its exact handoff, and reread T02 after its merge/rebase; do not treat the currently unmerged T09/T04 planning artifacts as merged facts.
3. Apply implementation only within the owned directories, then run pure unit tests and frontend build/strict/diff checks.
4. After backend gates are live, run real headless-browser role, approvals, users, 401/403/404/409, screenshot, and network evidence checks.
5. If rolled back, remove only the admin-operation view/API/store implementation; the frozen routes remain owned by T04 and continue to show their safe handoff/forbidden behavior.

## Open Questions

- None that can be safely guessed: the T09 artifact handoff is a blocking external gate for exact approval DTO mapping, not a deferrable design choice.
