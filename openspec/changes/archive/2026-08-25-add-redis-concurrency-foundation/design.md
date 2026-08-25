## Context

See `proposal.md` for motivation. The accepted application and security foundations require env-only credentials, safe startup defaults, fixed dependency versions, and no Redis/Redisson auto-configuration. T07 needs a Redisson watchdog-capable client for an optimization lock, while T12 will later need a Cache Aside-capable Spring Data Redis template. This change is the shared T01-owned seam; no business module may be added here.

## Goals / Non-Goals

**Goals:**

- Make Redis entirely opt-in, typed, validated, and testable from `common.config`.
- Keep disabled startup independent of Redis and free of client/connection-factory beans and connection attempts.
- Create one manually controlled Redisson client and one Spring Data Redis template foundation when enabled, with connect timeout `100..10000` ms (default `3000`), command timeout `100..30000` ms (default `5000`), and deterministic shutdown.
- Keep secrets out of logs and avoid unsafe native/polymorphic value deserialization.
- Preserve the database as the correctness source and give T07/T12 an explicit handoff contract.
- Provide a repository-root Docker Compose stack for repeatable local MySQL/Redis verification without committing credentials or exposing either service beyond loopback.

**Non-Goals:**

- No booking lock acquisition, watchdog usage in a business service, cache keys, TTL policy, Cache Aside flow, or availability serializer.
- No changes under `booking/**`, `resource/**`, `availability/**`, `cache/**`, `support/**`, frontend, SQL, or deploy directories.
- No Redis health/production-deployment exposure, generated committed credentials, migrations, or public API endpoints.

## Decisions

1. **Use direct Redisson core plus Spring Data Redis starter, with manual beans.** Pin `org.redisson:redisson:4.6.1`; official compatibility has been verified for Spring Boot 1.3 through 4.0, including this Boot 3.5 line. Prefer the core artifact over `redisson-spring-boot-starter` because the starter's auto-configuration would make bean creation and endpoint selection harder to keep strictly conditional. `spring-boot-starter-data-redis` remains BOM-managed for Spring Data abstractions and Lettuce classes.

2. **Disable competing Redis auto-configuration and own the conditional graph.** Exclude `RedisAutoConfiguration` and `RedisRepositoriesAutoConfiguration` in the application configuration, then create the connection factory, template, and Redisson client only from an enabled configuration class. This guarantees that disabled mode cannot silently instantiate a localhost `RedisConnectionFactory`; enabled mode has one source of truth for host, port, password, DB index, and timeouts.

3. **Bind a typed property object with enabled-only validation.** Map `REDIS_ENABLED` to `booking.redis.enabled` with default `false`; map host/port/password and timeout properties from environment placeholders: `REDIS_CONNECT_TIMEOUT_MS` → typed field `connectTimeoutMs` (`booking.redis.connect-timeout-ms`), a finite integer in inclusive range `100..10000` with default `3000`, and `REDIS_COMMAND_TIMEOUT_MS` → typed field `commandTimeoutMs` (`booking.redis.command-timeout-ms`), a finite integer in inclusive range `100..30000` with default `5000`. A custom enabled-mode validator (or equivalent conditional validation boundary) requires nonblank host, rejects ports outside `1..65535`, and rejects any timeout that is non-integer, non-finite, or outside its range before client construction or connection. Blank password is treated as absent. Defaults are non-secret and the DB index is fixed at `0`.

4. **Use explicit string/JSON boundaries.** The shared template uses `StringRedisSerializer` for keys and values; downstream code may write JSON strings using the shared application mapper or an explicit typed mapper. Do not use Java native serialization, default typing, or untrusted class metadata. The foundation does not invent business serializers.

5. **Close resources through Spring lifecycle ownership.** Register Redisson and the Spring Data connection factory as managed beans with destroy methods so context shutdown closes sockets and worker threads. Do not create clients in static initializers or per-request code.

6. **Preserve the approved Redisson watchdog default.** Leave Redisson's default watchdog timeout (30 seconds) enabled for the shared client and do not introduce a business `leaseTime` here. T07 must later acquire locks with a wait time only if it wants watchdog renewal; this foundation does not claim watchdog behavior for any lock operation it does not own.

7. **Keep failure policy with consumers.** A disabled or unavailable foundation is observable as absent/unusable beans. T07 must later fail closed at its lock-consumption boundary while retaining the `booking_slot` unique constraint; T12 may later fall back to its MySQL/read-through path when Cache Aside access fails. This foundation never converts Redis state into booking correctness or authorization.

8. **Validate with context tests and a required real-Redis profile.** Use context-runner tests for disabled, invalid enabled, boundary/default timeout values, and secret-safe logging behavior. For valid enabled configuration, verify bean wiring through an injectable construction seam/mock factory or a real temporary Redis, because `Redisson.create` may establish a network connection while constructing the client; the test must not assert that enabled context creation is network-free. A real Redis smoke suite must exercise startup, connectivity, string read/write, Redisson lock acquisition/release, watchdog-compatible configuration, and orderly application shutdown against a currently supported instance; missing test endpoint is a failed precondition, not a silently skipped test. Dependency tree compatibility output, Maven verify, strict OpenSpec validation, and diff checks are planning acceptance gates.

9. **Keep local service orchestration private and explicit.** A repository-root `docker-compose.yml` may start MySQL 8.0 and a supported Redis 7 instance solely for local verification. It must bind both published ports to `127.0.0.1`, mount the existing `sql/` migrations read-only for MySQL initialization, require `MYSQL_ROOT_PASSWORD` with no default value, define health checks, and contain no committed secret, generated credential, application service, or production deployment contract.

## Risks / Trade-offs

- [Risk] Excluding Boot Redis auto-configuration means future modules cannot assume conventional `spring.data.redis.*` auto-configured beans. → Mitigation: document the shared typed properties and expose one stable template/client contract for T07/T12.
- [Risk] Disabled mode may hide an operator's expectation that Redis is available. → Mitigation: enabled mode fails fast on malformed configuration; consumer-level availability/fallback behavior remains explicit and testable.
- [Risk] Direct Redisson core requires more explicit lifecycle wiring than the starter. → Mitigation: keep construction in one common configuration class and add context/shutdown tests.
- [Risk] String-only values move JSON typing responsibility to consumers. → Mitigation: keep the foundation serializer-neutral and require each later capability to specify an explicit, non-polymorphic DTO serializer in its own change.
- [Risk] A real Redis smoke test can be environment-sensitive. → Mitigation: require a documented supported instance and fail clearly when its test prerequisites are absent; never mark the test passed by skipping it.

## Migration Plan

1. Add the pinned dependencies and disabled-by-default placeholders; existing deployments continue without Redis.
2. Deploy with `REDIS_ENABLED=false` first and verify health/startup plus absence of Redis beans.
3. For local verification, set a temporary `MYSQL_ROOT_PASSWORD`, start the repository-root Compose stack, and pass loopback MySQL/Redis settings to the test process without persisting secrets.
4. For environments that need the foundation, provide `REDIS_HOST` (and optional port/password) through secret-managed environment configuration, then verify enabled context and smoke tests before handing off to T07/T12.
5. Roll back by setting `REDIS_ENABLED=false` or reverting this change; no database, cache-key, or production deployment migration is required.
