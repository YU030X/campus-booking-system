## 1. User mapper configuration

- [x] 1.1 Preserve the already-written `user/config/UserMapperConfiguration` with its user-only mapper scan; this is the only implementation fact currently complete.
- [x] 1.2 Add `booking.identity.enabled` with `havingValue=true` and `matchIfMissing=true` to `UserMapperConfiguration`, without changing resource mapper ownership.

## 2. Identity assembly gate

- [x] 2.1 Replace `@ConditionalOnBean(UserMapper.class)` with the same property gate on `UserService`, `UserController`, `AdminUserController`, `JwtConfig`, `AuthSecurityConfig`, `AuthService`, and `AuthController`; do not change common production security.
- [x] 2.2 Preserve the default-enabled identity behavior and make explicit `booking.identity.enabled=false` omit the mapper, seven identity components, and order-2 API chain while retaining common health/deny-all beans.

## 3. Production-context regression coverage

- [x] 3.1 Update the full `UserMapperRegistrationIntegrationTest`/MySQL acceptance path to set `booking.identity.enabled=true`, require the external MySQL 8 environment, and remove silent environment-variable skipping.
- [x] 3.2 In that real `BookingApplication` web context, assert exactly one `UserMapper`, all seven identity components, a `SecurityFilterChain` with order 2, register 201, login 200 Bearer, protected 401, and student-to-admin 403. Do not use hand-written identity `TestConfiguration` beans, mocks, stubs, or MyBatis/DataSource exclusions.
- [x] 3.3 Keep `UserMysqlIntegrationTest` on the real MySQL 8 path with the explicit true property; if prerequisites are absent, record the failed/not-run command rather than a pass.

## 4. Disabled foundation/manual coverage

- [x] 4.1 Set `booking.identity.enabled=false` explicitly in `SecurityContextIntegrationTest`, `SpringdocEnabledSecurityContextIntegrationTest`, `AuthSecurityChainIntegrationTest`, and other manual security contexts; assert identity beans are absent, `/api` is 403, and `/actuator/health` is 200 without using them as production-scan evidence.
- [x] 4.2 Keep any isolated security-boundary fixture clearly separate from production registration coverage and ensure no hand-written bean masks the enabled production scan.

## 5. Validation

- [x] 5.1 Run the explicit backend integration/test command against MySQL 8 and record the exact result; a missing external environment is not a silent skip or passing evidence.
- [x] 5.2 Run `openspec validate fix-user-mapper-registration --type change --strict`, a trailing-whitespace scan, `git diff --check`, and `git status --short`; resolve planning/whitespace failures and report the uncommitted worktree state.
