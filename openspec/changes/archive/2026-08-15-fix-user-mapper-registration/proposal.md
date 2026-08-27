## Why

An explicit `@MapperScan` in `ResourceMapperConfiguration` leaves `UserMapper` out of the production context. The failure is compounded by seven identity classes being evaluated through `@ConditionalOnBean(UserMapper.class)`, so the user/auth services, controllers, JWT configuration, and API security chain are not loaded; the common fallback `denyAll` chain then returns HTTP 403 for `/api` instead of allowing the documented auth flow.

## What Changes

- Keep the existing user-owned mapper scan and put `UserMapperConfiguration` plus the seven identity classes behind one `booking.identity.enabled` property gate (`true` by default, `matchIfMissing=true`).
- Remove production assembly dependence on `@ConditionalOnBean(UserMapper.class)` from `UserService`, `UserController`, `AdminUserController`, `JwtConfig`, `AuthSecurityConfig`, `AuthService`, and `AuthController`.
- Define the disabled contract: with `booking.identity.enabled=false`, no identity mapper/service/controller/security-chain beans are registered, while the common fallback keeps `/api` fail-closed and health remains public.
- Add enabled and disabled context coverage. Enabled coverage must use a full Spring Boot web context and real MySQL 8/MyBatis/DataSource infrastructure; disabled foundation/manual security tests must set the property explicitly to false.
- Assert exactly one `UserMapper`, all seven identity components, an `@Order(2)` API security chain, register 201, login 200, protected 401, and student-to-admin 403 in the enabled context. Do not use hand-written `TestConfiguration` beans to mask production scanning, and do not silently skip an external test when its environment is absent.
- Limit implementation paths to the existing user/auth classes, `user/config`, and corresponding tests. Do not modify `pom.xml`, common production configuration, SQL, or frontend code.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `identity-access`: make identity registration, login, and authorization availability explicit through the `booking.identity.enabled` gate while preserving the existing endpoint and error contracts.

## Impact

- Production planning scope covers `booking-api/src/main/java/com/yu030x/booking/user/config/UserMapperConfiguration.java`, the seven existing user/auth identity classes, and their corresponding tests.
- Enabled deployments retain the existing API behavior once the real mapper and security chain load; explicit disablement intentionally removes identity components and leaves common `/api` deny-all behavior.
- No database schema, dependency, resource-catalog, common production, or frontend contract changes.
