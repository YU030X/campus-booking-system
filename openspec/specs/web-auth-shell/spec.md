# web-auth-shell Specification

## Purpose

Provide a deterministic browser authentication shell for the frozen T02 identity API, covering validation, tab-scoped sessions, role-aware navigation, mock transport, and safe error recovery in both mock and real modes.

## Requirements

### Requirement: Authentication and session contract
The client MUST submit registration fields `username,password,realName` and optional `studentNo,phone,email`; `confirmPassword` SHALL remain client-only. Success MUST be 201 `data: UserView` with no auto-login, followed by a `/login` notice. Login success MUST be 200 `data:{token,tokenType:"Bearer",expiresIn:integer seconds,user}`; `/users/me` MUST return `UserView`. Forms MUST use exact validation (including TextEncoder byte approximation where needed), accessible labels/errors, loading and deduplication; they MUST show generic invalid-credentials and duplicate-business messages and MUST never log secrets.
#### Scenario: register and login
- **WHEN** valid credentials are submitted
- **THEN** register returns 201 UserView without auto-login and login returns the Bearer token contract.

### Requirement: Session lifecycle
The client MUST persist only `{token,tokenType,expiresAt}` in sessionStorage; it MUST NOT persist `user` or `expiresIn`. It MUST compute `expiresAt=Date.now()+expiresIn*1000` from the actual response. Early expiry SHALL use `Math.min(30, Math.max(0, Math.floor(expiresIn/10)))` seconds so short TTLs are not immediately discarded. Reload hydration MUST await one shared `/users/me` promise; success MUST keep user Pinia-only and failure MUST clear. Logout MUST clear. JWT parsing MUST NOT be authoritative.
#### Scenario: reload hydration
- **WHEN** a stored token is reloaded
- **THEN** one shared `/users/me` verifies it; success keeps user in memory and failure clears storage.

### Requirement: HTTP errors and safe redirects
Real mode MUST use `/api/v1` and Bearer injection. Login/register 401/4xx MUST return caller errors only. First `/users/me` or protected 401 MUST clear session, capture a safe relative path, and replace `/login`; concurrent failures MUST share one-shot promise/flag, reset after navigation, and MUST NOT loop on `/login`. 403 MUST preserve the token, set forbidden state, and block navigation without a new path. Safe redirect MUST accept only single-leading-slash relative paths and reject `//`, backslashes, controls, schemes, absolute URLs, and decoded bypasses.
#### Scenario: protected 401 and forbidden
- **WHEN** protected requests return 401/403
- **THEN** 401 clears once and replaces login with safe path; 403 preserves token and shows in-layout forbidden state.

### Requirement: Mock boundary
Mock MUST handle only exact baseURL-relative `/auth/register`, `/auth/login`, and `/users/me` GET/PUT paths (method matching normalized lowercase), plus controllable 401/403, with zero network. Unknown endpoints MUST return HTTP 404 with code `40400`, message `mock endpoint not implemented`, `data:null`, never success. Accounts MUST be in-memory; passwords MAY be held only for development comparison and MUST never be persisted to sessionStorage/localStorage or logged. Demo credentials are development-only and not production secrets. Real and mock success contracts MUST match.
#### Scenario: unknown mock endpoint
- **WHEN** a non-auth endpoint is requested in mock mode
- **THEN** HTTP 404 with code `40400`, message `mock endpoint not implemented`, and `data:null` is returned without network or success.

### Requirement: Routes and layout
The router MUST define exactly twelve route records: public `/login`,`/register`; four student-shared paths `/resources`, `/resources/:id`, `/bookings`, `/bookings/:id`; and six admin paths `/admin/categories`, `/admin/resources`, `/admin/rules`, `/admin/closures`, `/admin/approvals`, `/admin/users`. It MUST NOT define a catch-all record. A global `beforeEach` MUST detect `to.matched.length===0` and redirect to login or the role default. Student routes MUST allow `STUDENT,ADMIN`; admin routes MUST allow `ADMIN`. Anonymous users MUST redirect to login; role mismatch MUST abort and layout MUST show forbidden state. Menus MUST use verified user.
#### Scenario: role guard
- **WHEN** anonymous or wrong-role navigation occurs
- **THEN** anonymous goes login; wrong role aborts and layout renders forbidden state, with no new error path.

### Requirement: Scope
The change MUST modify only `App.vue`, `layouts/**`, `router/index.js`, `views/auth/**`, `stores/auth.js`, `api/auth.js`, `api/http.js`, and optional `api/authMock.js`. It MUST NOT modify package/main/shared/resource/booking files.
#### Scenario: scope check
- **WHEN** the change diff is inspected
- **THEN** only declared auth-shell paths are modified and no forbidden error routes or secrets are introduced.
