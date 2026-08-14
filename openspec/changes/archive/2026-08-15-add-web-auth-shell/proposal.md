## Why

The current Vue shell exposes placeholder routes but has no usable authentication flow, session recovery, or role-aware navigation. T04 now needs to provide a stable front-end boundary that can run against the same-wave reviewed T02 identity contract while remaining demonstrable without a backend.

## What Changes

- Add public login and registration views backed by a shared auth API/store, with loading, validation, server-error display, and duplicate-submit protection.
- Add authenticated layouts and route guards for the existing student and administrator route table, including safe redirect handling, role checks, and an in-layout forbidden state (no new public error paths).
- Extend the Axios boundary with mock/real adapters, sessionStorage token recovery, Bearer injection, one-shot 401 cleanup/redirect, and non-destructive 403 handling.
- Provide mock register/login/me/expiry/403 behavior without network requests; preserve real mode at `/api/v1` and consume T02 DTO/error/401/403 contracts without inventing fields.
- Keep App.vue as the router/layout entry point and leave resource, booking, shared contracts/types, package manifests, and main.js untouched.

## Capabilities

### New Capabilities

- `web-auth-shell`: Browser authentication shell, session lifecycle, mock/real transport, route protection, role-aware layouts, and auth error handling.

### Modified Capabilities

- None.

## Impact

- Frontend paths owned by this change: `booking-web/src/App.vue`, `src/layouts/**`, `src/router/index.js`, `src/views/auth/**`, `src/stores/auth.js`, `src/api/auth.js`, `src/api/http.js`, and optional `src/api/authMock.js` only.
- Consumes the same-wave reviewed `add-identity-access` identity capability (register/login/me DTOs and HTTP 401/403/error envelope); mock/UI/session/router work may apply from that reviewed contract before T02 merges, while real-mode requests remain under `/api/v1`.
- Browser real-mode integration, final exact-field reconfirmation, completion, spec sync/archive, and PR Ready/merge remain gated on the authoritative T02 change being merged and rebased; if the merged contract changes, update these artifacts and implementation/tests first.
- No package or lockfile changes, no backend/database changes, and no edits to resource/booking views or shared contracts/types.
