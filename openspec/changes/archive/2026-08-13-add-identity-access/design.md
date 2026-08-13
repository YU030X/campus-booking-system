## Context

Identity is isolated from booking persistence and composes with the predecessor data/security foundation. T02 owns only `auth/**`, `user/**`, and corresponding tests.

## API and validation

Endpoints are exactly `/api/v1/auth/register`, `/api/v1/auth/login`, `/api/v1/users/me` (GET, PUT), `/api/v1/admin/users` (GET), and `/api/v1/admin/users/{id}/status` (PATCH). RegisterRequest and LoginRequest reject unknown JSON fields; PUT me and PATCH status do likewise, while undeclared GET query parameters are ignored. RegisterRequest validates username trim then non-empty with `^[A-Za-z0-9_]{3,50}$`, password without trim at 8..72 UTF-8 bytes (BCrypt max 72 bytes), realName trim 1..50, studentNo null or trimmed <=30, phone null or matching `^1[3-9]\d{9}$`, email null or Bean Validation-valid <=100; all optional strings trim and normalize empty to null, while password is never trimmed and username/realName must remain non-empty. `confirmPassword` is frontend-only. Register returns 201 `data: UserView` and does not log in. Login returns 200 `{token,tokenType:"Bearer",expiresIn:<actual TTL seconds>,user}`, where configured JWT_TTL_SECONDS 1..86400 is returned verbatim and defaults to 7200; expiresIn equals token exp-iat within clock precision. UserView fields are exactly id(string), username, realName, studentNo, phone, email, avatar, role, creditScore, status, createdAt, updatedAt; never password/deleted.

PUT is a full replacement with required realName and nullable phone/email/avatar; unknown fields fail 400. Admin list defaults pageNumber=1/pageSize=10, bounds 1..100, keyword trim <=100, status 0|1, role STUDENT|ADMIN, and orders createdAt desc,id desc. PATCH body is numeric `{status:0|1}`.

## Security implementation

`auth/security/**` contains SecurityFilterChain (matching `/api/v1/**`), JWT filter, encoder/decoder, and principal adapter. Register/login are permitAll; all others authenticated; admin methods require ADMIN. JWT uses HS256, env JWT_SECRET UTF-8 >=32 bytes (startup failure without logging value), JWT_TTL_SECONDS 1..86400 (default 7200), <=30s clock skew, claims sub decimal id string, username, role, iat, exp, strict one-token Bearer syntax, and rejects malformed/missing claims or unknown roles. Disabled/soft-deleted users are rechecked each request. BCrypt strength is 12; nonexistent login performs a dummy hash check. T02's `/api/v1/**` chain is `@Order(2)`, after health `@Order(1)` and before fallback; any docs auth chain must be a separate owner/auth-security chain and must not alter common.

## Data and errors

Logical uniqueness is `(username,deleted)`: only deleted=0 conflicts; deleted=1 history can be reused, preserving the known single-deleted-row DDL limitation. Translate concurrent duplicate keys. Use existing codes: 40000 invalid parameter, 40100 unauthenticated (login failures uniformly “账号或密码错误”), 40300 forbidden, 40400 user not found, 41000 username already exists or administrator cannot disable self; data is null. Disabled old tokens, malformed/missing Bearer, wrong credentials, and disabled/soft-deleted login follow the specified 401 wording.
