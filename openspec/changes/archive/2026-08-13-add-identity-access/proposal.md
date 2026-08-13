## Why

Campus clients and administrators need a durable identity boundary before booking APIs are exposed. This change defines exact authentication, profile, and administration contracts that downstream modules can consume without inspecting persistence internals.

## What Changes

- Add `/api/v1/auth/register` and `/api/v1/auth/login` with BCrypt strength 12 and HS256 access-only JWTs (no refresh tokens).
- Add authenticated `GET/PUT /api/v1/users/me` with strict DTO allowlists and exact `UserView` JSON.
- Add ADMIN-only user listing and idempotent status management at `/api/v1/admin/users/{id}/status`.
- Define uniform validation/authentication/authorization/conflict/not-found envelopes using existing error codes.
- Login failures for wrong, nonexistent, disabled, or soft-deleted accounts use HTTP 401 code 40100 with message `账号或密码错误`; non-login Bearer authentication failures remain `unauthenticated`.
- Expose a principal accessor for current user id, username, and role; ownership rules remain downstream service responsibilities.
- Do not modify common/Jackson, pom, SQL, shared error definitions, booking code, or seed a default administrator.

## Capabilities

### New Capabilities

- `identity-access`: registration, login, profile, administrator user management, JWT validation, authorization, lifecycle, and transport contracts.

### Modified Capabilities

- None.

## Impact and dependency

- Implementation and tests are restricted to `booking-api/src/main/java/com/yu030x/booking/auth/**`, `.../user/**`, and matching test packages. All security chain/JWT filter/encoder/decoder classes live under `auth/security/**`.
- Requires the merged `extend-backend-data-security-foundation` extension points for persistence, JOSE dependencies, and composable security configuration; if unavailable, implementation must pause and request shared-file scope rather than edit common files.
