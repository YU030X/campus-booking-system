## MODIFIED Requirements

### Requirement: Exact registration and login contracts
The API SHALL expose POST `/api/v1/auth/register` and POST `/api/v1/auth/login` when identity is enabled. RegisterRequest and LoginRequest SHALL reject unknown JSON fields; validation SHALL match the design (including UTF-8 byte password length and trimming/null normalization). Registration returns HTTP 201 with `data: UserView` and no token. Login returns HTTP 200 `{token,tokenType:"Bearer",expiresIn:<actual TTL seconds>,user}` with integer seconds equal to exp-iat within clock precision; JWT_TTL_SECONDS 1..86400 is returned verbatim, default 7200. Passwords use BCrypt strength 12 and are never returned or logged. With the default or explicit `booking.identity.enabled=true`, these contracts SHALL be reachable in the production Spring context through the real user persistence mapper.

#### Scenario: successful registration
- **WHEN** a unique valid registration is submitted and the resulting credentials are used to log in
- **THEN** registration returns HTTP 201 with a STUDENT UserView and login returns HTTP 200 with a Bearer token response

#### Scenario: duplicate and logical reuse
- **WHEN** active username insertion conflicts, including a concurrent race
- **THEN** HTTP 409 code 41000 message `username already exists`; deleted=1 history does not conflict

#### Scenario: uniform login failure
- **WHEN** login credentials are wrong or the account is disabled/soft-deleted
- **THEN** HTTP 401 code 40100 `账号或密码错误` is returned
- **WHEN** a protected request has missing/malformed/expired/tampered/duplicated/empty/other-scheme Bearer authentication
- **THEN** HTTP 401 code 40100 `unauthenticated` is returned

### Requirement: JWT and principal boundary
JWT SHALL be HS256 access-only with env secret/TTL constraints, claims and <=30s clock skew from design, strict Bearer parsing, and disabled-user recheck. A principal accessor SHALL expose only current id, username, and role; ownership checks remain services. When identity is enabled, the production context SHALL load the authentication/security chain and its user mapper dependency without test-only mocks or exclusions.

#### Scenario: disabled old token
- **WHEN** a token issued before disable is reused
- **THEN** HTTP 401 code 40100 `unauthenticated` is returned

#### Scenario: role and principal enforcement
- **WHEN** an authenticated STUDENT invokes an ADMIN method
- **THEN** HTTP 403 code 40300 `forbidden` is returned and no mapper is exposed through the accessor

#### Scenario: anonymous and role boundaries
- **WHEN** an anonymous client invokes a protected user endpoint
- **THEN** HTTP 401 code 40100 `unauthenticated` is returned
- **WHEN** an authenticated STUDENT invokes an ADMIN method
- **THEN** HTTP 403 code 40300 `forbidden` is returned

## ADDED Requirements

### Requirement: Identity availability gate
Identity persistence, user services/controllers, JWT configuration, authentication services/controllers, and the API security chain SHALL be enabled when `booking.identity.enabled` is `true` or missing. When the property is explicitly `false`, those identity components SHALL not be registered; the common health endpoint remains public and the common fallback denies API requests.

#### Scenario: default or explicit enablement
- **WHEN** the application starts with no `booking.identity.enabled` value or with `booking.identity.enabled=true`
- **THEN** exactly one `UserMapper` is available, the identity registration/login/profile/admin/security components are available, the API security chain includes order 2, and register/login/protected requests can produce 201/200/401 while a STUDENT admin request produces 403

#### Scenario: explicit disablement is fail-closed
- **WHEN** the application starts with `booking.identity.enabled=false`
- **THEN** no identity mapper, service, controller, JWT, or API security-chain component is registered, `/api` returns 403, and `/actuator/health` returns 200

### Requirement: Production user mapper registration
When identity is enabled, the production Spring application context SHALL contain exactly one `UserMapper` bean backed by the configured MyBatis/DataSource infrastructure and SHALL load the seven identity components (`UserService`, `UserController`, `AdminUserController`, `JwtConfig`, `AuthSecurityConfig`, `AuthService`, and `AuthController`). Verification SHALL use the real MySQL 8 integration path and a full Spring Boot web context; it SHALL NOT satisfy the requirement by hand-mocking `UserMapper`, supplying hand-written identity `TestConfiguration` beans, excluding MyBatis/DataSource auto-configuration, or replacing the mapper with a test stub. External prerequisites SHALL fail visibly rather than be silently skipped.

#### Scenario: mapper and auth beans are present
- **WHEN** the application context starts against the real MySQL 8 integration database with identity enabled
- **THEN** exactly one user mapper and all seven identity components are available, the order-2 API chain is present, and register/login requests execute through the normal auth chain
