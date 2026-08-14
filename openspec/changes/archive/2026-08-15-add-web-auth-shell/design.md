## Context

`booking-web` currently has a buildable Vue shell, frozen API/type contracts, and placeholder routes. T04 owns the route shell and Axios boundary, while T02 owns the identity DTO and exact authentication error contract. See `proposal.md` and `specs/web-auth-shell/spec.md`; implementation must stop at the listed T04 paths.

## Goals / Non-Goals

**Goals:**

- Keep auth views independent of transport mode through `auth.js` API functions and a Pinia `auth` store.
- Make session restoration, one-shot 401 handling, role guards, and safe redirects deterministic.
- Provide a browser-only mock adapter with controllable valid, expired, and forbidden cases.
- Preserve existing route paths and leave downstream feature views as placeholders owned by later changes.

**Non-Goals:**

- No refresh token, OAuth/OIDC, cookie migration, backend identity implementation, or API contract revision.
- No package, lockfile, `main.js`, shared contract/type, resource, booking, or direct component-Axios edits.

## Decisions

1. **Single auth boundary.** `src/api/auth.js` owns register/login/me/update-me calls and normalizes only the frozen T02 envelope; views call store actions, never Axios. This keeps mock and real behavior interchangeable. A direct-view Axios approach was rejected because it duplicates error/session policy.

2. **Session storage record.** Store only `{token,tokenType,expiresAt}` under one sessionStorage key; keep `user` Pinia-only and never persist `expiresIn`. Compute `expiresAt` from the actual T02 `expiresIn` value. Hydration verifies `/users/me` once; `localStorage` is rejected because persistence is tab-scoped.

3. **Central interceptors with loop guard.** Login/register 4xx return to callers. `/users/me` or protected 401 clears once, captures a validated safe path, replaces `/login`, and resets after navigation settles; concurrent failures share one promise/flag. 403 preserves token, sets forbidden state, and aborts navigation; layout renders the state without a new path.

4. **Router metadata over duplicated route logic.** Keep exactly twelve route records (2 public, 4 student-shared, 6 admin) and add `meta.public`/`meta.roles`; role mismatch aborts and layout renders forbidden state. Do not add a catch-all record: `beforeEach` detects `to.matched.length===0` and redirects to login or the role default.

5. **Role layouts and default pages.** A small authenticated layout supplies navigation and logout; menu entries are filtered from the verified `user.role`, but route metadata and backend remain authoritative. Default destinations are `/resources` for students and `/admin/resources` for admins unless T02 or product review freezes another default before apply.

6. **Mock adapter state.** Mock data lives only in the auth API/http boundary (or an explicitly scoped auth mock helper), with an in-memory account map and deterministic token records. It returns the same success/error envelope, simulates expiry and 403, and never calls `fetch`/XHR. Mock passwords may be held in memory solely for development comparison and are never persisted, stored in session/local storage, or logged.

## Risks / Trade-offs

- [sessionStorage token is XSS-sensitive] → never log or render token/password; retain the documented short-lived-token limitation and avoid expanding persistence.
- [T02 DTO fields or error envelope may differ before merge] → allow mock/UI/session/router apply from the reviewed contract, but gate real-mode integration and completion on the merged authoritative contract; update these artifacts and implementation/tests if fields/statuses change.
- [Concurrent 401 responses can race] → use one redirect/clear guard and reset it after navigation; test multiple rejected requests.
- [Mock behavior can diverge from backend] → keep auth API signatures identical and run both mode checks plus browser evidence.
- [Placeholder feature components may not expose full role behavior] → test guards at router level and leave feature implementation to owning changes.

## Migration Plan

1. Apply only the allowed T04 files; run frontend build and static scope/secret checks.
2. Run browser checks in mock mode (zero network, register/login, refresh, expiry, logout, student/admin/403) and real mode against `/api/v1` when T02 is available.
3. Capture screenshots/evidence, then sync the capability spec and strict-validate before a Draft PR.
4. Rollback is deleting/reverting the T04-owned files; no database or server migration is required.

## Resolved dependency strategy

- Apply may begin after reading the same-wave reviewed `add-identity-access` identity capability from the peer worktree or PR. This unblocks mock transport, UI, session, and router work without inventing fields; real transport and exact-field verification remain gated.
- After T02 merges, re-read the authoritative main spec and re-confirm register/login/me fields, expiry representation, statuses, and envelopes before marking tasks complete or running real browser integration. If the merged contract differs, update proposal/spec/design/tasks and implementation/tests before proceeding.
