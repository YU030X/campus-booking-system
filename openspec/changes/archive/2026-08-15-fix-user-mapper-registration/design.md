## Context

The existing `UserMapperConfiguration` already owns a narrow `@MapperScan` for the user mapper, but seven identity classes still use `@ConditionalOnBean(UserMapper.class)`. Conditional evaluation can leave the complete user/auth chain absent; `SecurityConfig` then supplies the common `denyAll` fallback. See proposal.md for the motivation. The design must preserve resource ownership and the existing identity-access API contracts.

## Goals / Non-Goals

**Goals:**

- Make one property gate control mapper, identity services/controllers, JWT configuration, and the API security chain.
- Keep the default production behavior enabled when the property is missing.
- Prove both enabled production behavior and explicit disabled fail-closed behavior with observable context and HTTP assertions.

**Non-Goals:**

- Do not broaden or rewrite `ResourceMapperConfiguration` or edit common production `SecurityConfig`.
- Do not alter Maven dependencies, SQL, frontend code, endpoint payloads, or existing authorization rules.
- Do not use `@ConditionalOnBean` as a production assembly gate, or use hand-written test beans to stand in for production scanning.

## Decisions

1. **Use a shared property gate.** Apply `@ConditionalOnProperty(prefix = "booking.identity", name = "enabled", havingValue = "true", matchIfMissing = true)` to `UserMapperConfiguration` and to `UserService`, `UserController`, `AdminUserController`, `JwtConfig`, `AuthSecurityConfig`, `AuthService`, and `AuthController`. Remove each class's `@ConditionalOnBean(UserMapper.class)` assembly condition. The default is enabled for backward compatibility; `false` prevents all identity beans from registering and leaves the common health and deny-all chains untouched. Alternative rejected: another bean-presence condition, because it recreates ordering-sensitive evaluation.

2. **Keep mapper ownership narrow.** Retain the existing `user/config/UserMapperConfiguration` scan targeting `UserMapper.class` only. Do not expand the resource scan or restore global implicit scanning. The enabled context must assert exactly one `UserMapper` bean.

3. **Separate production and foundation verification.** The enabled acceptance test uses `BookingApplication` in a full `RANDOM_PORT` Spring Boot web context, sets `booking.identity.enabled=true`, and connects to the real MySQL 8 schema through the normal DataSource/MyBatis path. It must assert the seven named identity components, exactly one mapper, a `SecurityFilterChain` with order 2, register 201, login 200, protected 401, and student-to-admin 403. Foundation/manual security contexts set `booking.identity.enabled=false` explicitly and assert the seven components and mapper are absent, `/api` is 403, and `/actuator/health` is 200. No acceptance evidence may come from `TestConfiguration` identity beans, mapper mocks, MyBatis/DataSource exclusions, or an environment-variable skip.

4. **Fail visibly when external prerequisites are absent.** MySQL integration tests must declare the enabled property and rely on the required DB environment; they must not use `@EnabledIfEnvironmentVariable` or another silent skip. A missing environment is a failed/not-run validation condition to report, never a passing result.

5. **Declare the implementation boundary.** Allowed production paths are the existing `booking-api/src/main/java/com/yu030x/booking/user/config/UserMapperConfiguration.java`, the seven existing user/auth classes named in Decision 1, and corresponding tests under `booking-api/src/test/java/com/yu030x/booking/user`, `.../auth`, and `.../common/config`. The change must not touch `pom.xml`, `booking-api/src/main/java/com/yu030x/booking/common`, `sql/`, or `booking-web/`.

## Risks / Trade-offs

- [Risk] A gate is added to one identity class but omitted from another → enumerate all seven classes plus `UserMapperConfiguration` in the implementation checklist and assert their bean presence/absence in both contexts.
- [Risk] Mapper scans overlap → retain the class-scoped scan and assert exactly one `UserMapper` bean.
- [Risk] MySQL credentials or schema are unavailable → run the explicit external command, fail rather than skip, and report the exact prerequisite/result.
- [Risk] Disabling identity accidentally weakens unrelated routes → keep `SecurityConfig` unchanged and assert health 200 plus `/api` 403 in the disabled context.

## Migration Plan

1. Add the gate annotations/removals only in the existing user/auth classes and retain the already-written mapper scan.
2. Update the production MySQL web-context test to set `booking.identity.enabled=true`, remove silent skipping, and add the complete bean/order/HTTP assertions.
3. Set `booking.identity.enabled=false` in foundation/manual security tests and add the disabled-context assertions without production identity test beans.
4. Run strict OpenSpec validation, whitespace/diff checks, and the explicit MySQL command; record failures or missing external prerequisites without checking tasks prematurely.
5. Roll back by reverting only the gate/test edits; no schema migration is required.
