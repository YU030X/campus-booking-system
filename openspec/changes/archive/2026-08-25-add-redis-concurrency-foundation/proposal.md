## Why

T07's Redisson booking lock and T12's Cache Aside work need one reviewed, shared Redis configuration boundary owned by T01. Today the backend foundation explicitly excludes Redis/Redisson, so later changes would otherwise duplicate dependencies, create unsafe localhost defaults, or accidentally make Redis a correctness source. This change establishes an opt-in, fail-fast Redis foundation while keeping the application fully runnable with Redis disabled.

## What Changes

- Add a shared `common.config` Redis foundation with typed `booking.redis.*` properties and an env-backed `booking.redis.enabled` switch (default `false`).
- Pin `spring-boot-starter-data-redis` through the Spring Boot BOM and pin `org.redisson:redisson` to `4.6.1`; use explicit common configuration rather than uncontrolled Redis/Redisson auto-configuration.
- When disabled, create neither `RedissonClient` nor `RedisConnectionFactory`; startup and health remain successful with zero Redis connection attempts, secret, generated credential, or password/URI log.
- When enabled, require a nonblank `REDIS_HOST`, validate `REDIS_PORT` in `1..65535` (default `6379`), accept an optional blank `REDIS_PASSWORD` as absent, use DB index `0`, and bind the typed `connectTimeoutMs` field (`booking.redis.connect-timeout-ms`) from `REDIS_CONNECT_TIMEOUT_MS` as a finite integer number of milliseconds in `100..10000` (default `3000`), plus the typed `commandTimeoutMs` field (`booking.redis.command-timeout-ms`) from `REDIS_COMMAND_TIMEOUT_MS` as a finite integer number of milliseconds in `100..30000` (default `5000`). Invalid enabled host/port/timeout values fail before client construction or any connection attempt.
- Exclude `RedisAutoConfiguration` and `RedisRepositoriesAutoConfiguration`, then create the enabled client graph manually. A valid enabled context may connect while `Redisson.create` constructs the client; acceptance must therefore use a real temporary Redis smoke or an injectable construction seam/mock factory, never a blanket no-network assertion.
- Provide conditionally created Redisson and Spring Data Redis client/template foundations, safe string/JSON serialization without Java-native polymorphic deserialization, and deterministic shutdown of clients.
- Define the handoff contract: T07 must fail closed at its business layer if the client is unavailable, while T12 may fall back to its database/read-through behavior when cache access is unavailable; neither feature is implemented here and MySQL remains the correctness source.
- Add focused context/configuration, dependency-audit, and opt-in real-Redis smoke-test plans.
- Add a repository-root `docker-compose.yml` for local verification only, with MySQL and Redis bound exclusively to loopback, an explicitly supplied MySQL root password, no Redis public exposure, and no production deployment changes.

## Capabilities

### New Capabilities

- `shared-redis-concurrency-foundation`: Opt-in, typed, secure shared Redis/Redisson client and Spring Data Redis foundation for later lock and cache changes.

### Modified Capabilities

- `backend-data-security-foundation`: Revise the dependency-audit contract so the explicitly pinned, conditionally configured Redis foundation is allowed while dynamic versions, Flyway, business starters, secrets, and Redis-as-correctness remain prohibited.

## Impact

- Owned implementation scope is limited to repository-root `docker-compose.yml`, `booking-api/pom.xml`, `booking-api/src/main/resources/application.yml`, `booking-api/src/main/java/com/yu030x/booking/common/config/**`, corresponding common-config tests, and these OpenSpec artifacts. Business booking/resource/availability/cache/support packages, frontend, SQL contents, and deploy files are out of scope.
- Adds Redis/Redisson dependencies and conditional runtime beans, but no new HTTP API or business cache/lock keys.
- Requires CI/local validation against dependency resolution and a currently supported Redis instance; no public Redis exposure is introduced.
- No implementation, commit, push, spec sync, or archive is authorized by this planning change.
