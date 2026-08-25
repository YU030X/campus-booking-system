## 1. Dependency and configuration contract

- [x] 1.1 Update only `booking-api/pom.xml` to add `spring-boot-starter-data-redis` through the Spring Boot BOM and `org.redisson:redisson` pinned to `4.6.1`; confirm no dynamic/SNAPSHOT versions and no Redisson starter auto-configuration dependency.
- [x] 1.2 Update only `booking-api/src/main/resources/application.yml` with `booking.redis.enabled=${REDIS_ENABLED:false}`, non-secret host/port/password/database placeholders, `booking.redis.connect-timeout-ms=${REDIS_CONNECT_TIMEOUT_MS:3000}`, and `booking.redis.command-timeout-ms=${REDIS_COMMAND_TIMEOUT_MS:5000}`; both timeout properties are finite integer milliseconds, respectively constrained to `100..10000` and `100..30000`; exclude both `RedisAutoConfiguration` and `RedisRepositoriesAutoConfiguration` so disabled mode cannot create a default localhost connection factory.

## 2. Typed shared Redis foundation

- [x] 2.1 Add typed `booking.redis` properties under `booking-api/src/main/java/com/yu030x/booking/common/config/**`, including enabled-only validation for nonblank host, port range `1..65535`, optional blank password, DB index `0`, typed `connectTimeoutMs` as a finite integer millisecond value `100..10000` defaulting to `3000`, and typed `commandTimeoutMs` as a finite integer millisecond value `100..30000` defaulting to `5000`; invalid enabled values must fail before client construction or connection.
- [x] 2.2 Add conditional common configuration that creates exactly one managed Redisson client and one Spring Data Redis connection/template foundation only when enabled and valid; wire endpoint, optional password, DB index, finite timeouts, and deterministic destroy/close methods.
- [x] 2.3 Configure explicit `StringRedisSerializer` key/value boundaries (with documented JSON-as-string handoff) and verify that Java native serialization, default typing, credential-bearing URIs, and business cache/lock keys are absent.
- [x] 2.4 Keep the foundation limited to the allowed `common/config/**` paths and document the T07/T12 handoff: T07 lock consumption is fail-closed, T12 cache consumption may fall back to MySQL/read-through behavior, MySQL remains authoritative, and this foundation does not implement either business policy.

## 3. Context and security tests

- [x] 3.1 Add corresponding common-config tests using `ApplicationContextRunner` (or equivalent) proving disabled mode starts, health can initialize, and no `RedissonClient` or `RedisConnectionFactory` bean exists.
- [x] 3.2 Add enabled-valid acceptance using either an injectable Redisson construction seam/mock factory or a real temporary Redis: prove both shared foundations are defined without requiring a secret, and do not assert that context creation is network-free because `Redisson.create` may connect.
- [x] 3.3 Add invalid-enabled tests for blank host, non-numeric/out-of-range port, non-integer/non-finite timeout values, and timeout values below/above the inclusive connect range `100..10000` or command range `100..30000`; add default and exact-boundary cases; assert actionable failure before Redisson/Spring client construction or any connection attempt.
- [x] 3.4 Add log-capture/configuration tests proving passwords, credential-bearing URIs, generated secrets, and full command credentials never appear in diagnostics.
- [x] 3.5 Add serializer tests proving string/explicit JSON values round-trip and untrusted Java type metadata cannot trigger native polymorphic deserialization; add lifecycle tests proving clients close on context shutdown.

## 4. Real Redis smoke and handoff evidence

- [x] 4.1 Add a real-Redis integration smoke test under the corresponding common-config test area for a currently supported temporary/isolated instance, covering application startup, connect, string read/write, Redisson lock acquire/release, watchdog-compatible configuration, and orderly context/client shutdown; missing test endpoint/credentials must fail the test precondition rather than silently skip.
- [x] 4.2 Record the required environment contract (`REDIS_CONNECT_TIMEOUT_MS` integer `100..10000` default `3000`; `REDIS_COMMAND_TIMEOUT_MS` integer `100..30000` default `5000`) and dependency compatibility evidence for the smoke test without committing secrets or changing `deploy/**`; verify Redis remains private and no password/URI is logged.
- [x] 4.3 Add repository-root `docker-compose.yml` for local MySQL 8.0 and supported Redis 7 verification, requiring `MYSQL_ROOT_PASSWORD`, mounting `sql/` read-only, publishing only to `127.0.0.1`, and defining health checks without committed secrets or production deployment behavior.

## 5. Validation and boundary audit

- [x] 5.1 Run `mvn dependency:tree` and preserve compatibility evidence showing Spring Boot `3.5.4` BOM resolution, `spring-boot-starter-data-redis`, direct Redisson `4.6.1`, no dynamic/SNAPSHOT/Flyway/business starter, and no accidental Redis auto-config path.
- [x] 5.2 Run the focused common-config tests, the required real-Redis smoke test, and `mvn verify`; preserve exact command output and distinguish unavailable external prerequisites from passing results.
- [x] 5.3 Run `openspec validate add-redis-concurrency-foundation --type change --strict --no-interactive` and `git diff --check`; audit `git status --short` to ensure only the explicitly allowed files and OpenSpec artifacts changed.
- [x] 5.4 Before handoff, confirm no implementation work has expanded into booking/resource/availability/cache/support/frontend/sql/deploy paths and leave T07/T12 business lock/cache behavior for their own changes.
- [x] 5.5 Start the root Compose stack with a temporary password, verify both health checks and loopback-only port publication, rerun the real-Redis smoke test and `mvn verify`, then stop/remove the verification containers and rerun strict OpenSpec validation plus `git diff --check`.

## Acceptance evidence

- `mvn dependency:tree` → BUILD SUCCESS; Boot-managed `spring-boot-starter-data-redis:3.5.4`, direct `org.redisson:redisson:4.6.1`, no Redisson starter or Flyway.
- `mvn -Dtest=RedisFoundationConfigurationTest,ApplicationConfigurationStaticTest test` → 19 tests, 0 failures/errors/skips.
- With root Compose healthy and `REDIS_HOST=127.0.0.1`, `mvn -Dtest=RedisRealIntegrationTest test` → 1 test, 0 failures/errors/skips; full application startup, health, String RedisTemplate/Redisson round trips, watchdog-compatible lock, and shutdown assertions passed.
- With temporary MySQL/Redis/JWT environment values, `mvn verify` → 91 tests, 0 failures/errors/skips, BUILD SUCCESS.
- Root Compose health checks → MySQL and Redis healthy; published bindings verified as `127.0.0.1:3306` and `127.0.0.1:6379`; `docker compose down` removed containers/network and left no listeners.
- `openspec validate --specs` → 11 passed, 0 failed; `openspec validate add-redis-concurrency-foundation --type change --strict --no-interactive` → valid.
- `git diff --check` → passed.
