# Shared Redis Concurrency Foundation Specification

## Purpose

Provides a secure, opt-in Redis client foundation that later booking locks and Cache Aside consumers can share without making Redis a required dependency for application startup or a source of business correctness.

## Requirements

### Requirement: Redis is explicitly opt-in and environment-backed

The service MUST expose a typed `booking.redis.enabled` switch backed by `REDIS_ENABLED` and defaulting to `false`. When enabled, `REDIS_HOST` MUST be present and nonblank; `REDIS_PORT` MUST be an integer from `1` through `65535` (default `6379` is permitted); `REDIS_PASSWORD` MAY be blank and a blank value MUST be treated as absent; the selected database index MUST be `0`; typed field `connectTimeoutMs` (`booking.redis.connect-timeout-ms`) MUST bind from `REDIS_CONNECT_TIMEOUT_MS` as a finite integer number of milliseconds in the inclusive range `100..10000` (default `3000`); and typed field `commandTimeoutMs` (`booking.redis.command-timeout-ms`) MUST bind from `REDIS_COMMAND_TIMEOUT_MS` as a finite integer number of milliseconds in the inclusive range `100..30000` (default `5000`). Invalid enabled host, port, or timeout configuration—including non-integer, non-finite, or out-of-range timeout values—MUST fail before Redisson/Spring client construction or any Redis connection attempt, with an actionable configuration error.

#### Scenario: Disabled startup has no Redis requirement

- **WHEN** the application starts with `REDIS_ENABLED` absent or false and no Redis host, password, or generated secret
- **THEN** startup and health succeed with zero Redis connection attempts, without creating a `RedissonClient` or `RedisConnectionFactory` bean, and without logging credentials or a generated secret.

#### Scenario: Enabled configuration is validated before use

- **WHEN** `REDIS_ENABLED=true` and `REDIS_HOST` is blank, `REDIS_PORT` is non-numeric/out of range, `REDIS_CONNECT_TIMEOUT_MS` is not a finite integer in `100..10000` (default `3000`), or `REDIS_COMMAND_TIMEOUT_MS` is not a finite integer in `100..30000` (default `5000`)
- **THEN** application startup/context refresh fails fast with an actionable configuration error before Redisson/Spring client construction, any Redis connection attempt, or business endpoints becoming ready.

### Requirement: Enabled mode exposes bounded shared client foundations

When `booking.redis.enabled` is true and configuration is valid, the service MUST expose one shared Redisson client and one Spring Data Redis connection/template foundation using the configured endpoint, optional password, database `0`, connect timeout `100..10000` ms (default `3000`), and command timeout `100..30000` ms (default `5000`). The Redisson client MUST retain the approved default watchdog behavior (30-second watchdog timeout) and this foundation MUST NOT introduce a business `leaseTime`. The foundation MUST close its clients deterministically during application shutdown. It MUST NOT expose business cache entries, booking lock keys, or business-specific serializers.

#### Scenario: Valid enabled mode creates reusable beans

- **WHEN** the application context starts with valid enabled configuration and acceptance uses a real temporary Redis or an injectable construction seam/mock factory
- **THEN** downstream modules can resolve the shared Redisson and Spring Data Redis foundation beans without each creating its own client or endpoint configuration; the contract makes no no-network promise for context creation because `Redisson.create` may establish a connection while constructing the client.

#### Scenario: Shutdown closes clients

- **WHEN** the application context is closed after enabled startup
- **THEN** the shared Redis clients are closed and no background client resources remain.

### Requirement: Redis foundation protects secrets and serialization boundaries

The foundation MUST never log a Redis password, credential-bearing URI, or equivalent secret. Redis values MUST use a safe, explicitly selected string/JSON-compatible representation and MUST NOT enable Java native polymorphic deserialization or accept untrusted type metadata by default.

#### Scenario: Secret-safe diagnostics

- **WHEN** enabled configuration is logged or a connection/command error is reported
- **THEN** host/port context may be included, but passwords, full credential-bearing URIs, and generated secrets are absent.

#### Scenario: Safe value decoding

- **WHEN** a downstream consumer reads a foundation-provided value
- **THEN** decoding uses the approved string/JSON boundary and does not instantiate arbitrary Java classes from payload metadata.

### Requirement: Redis availability is not business correctness

The foundation MUST provide client beans only; it MUST NOT decide booking success, slot ownership, cache truth, or authorization. Consumers MUST be able to detect an unavailable/disabled client and handle that condition at their own business boundary; later T07 lock consumption MUST fail closed, while later T12 cache consumption MAY fall back to its MySQL/read-through path. T07 locking remains an optimization over the database unique constraint, and T12 caching remains Cache Aside over MySQL.

#### Scenario: Downstream absence is explicit

- **WHEN** a later consumer runs with Redis disabled or unavailable
- **THEN** T07 observes the missing/unusable foundation and fails closed, while T12 may use its database-safe/read-through fallback; neither consumer treats Redis as authoritative state, and this foundation does not implement those business policies.

### Requirement: Local verification services remain private and secret-free

The repository MUST provide a root `docker-compose.yml` for local verification with MySQL 8.0 and a currently supported Redis 7 image. Published MySQL and Redis ports MUST bind only to `127.0.0.1`; MySQL initialization MUST mount the existing `sql/` directory read-only; `MYSQL_ROOT_PASSWORD` MUST be explicitly supplied without a committed default; health checks MUST cover both services. The Compose stack MUST NOT contain a committed credential, expose either service publicly, define application production deployment, or make Redis authoritative.

#### Scenario: Local verification stack starts safely

- **WHEN** a developer supplies a temporary `MYSQL_ROOT_PASSWORD` and starts the root Compose stack
- **THEN** MySQL initializes `booking_db` from the existing SQL scripts, Redis answers connectivity checks, both services remain reachable only through loopback-published ports, and no secret is written to the repository.
