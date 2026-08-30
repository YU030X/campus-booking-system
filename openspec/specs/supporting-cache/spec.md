# Supporting Cache Specification

## Purpose

Define an optional, correctness-preserving Cache Aside layer for the availability query without making Redis a booking authority.

## Requirements

### Requirement: Independent cache flag and exact availability key

The cache capability MUST be controlled by its own shared-config feature flag, defaulting to `false`; when disabled, the availability service MUST perform its existing MySQL calculation and MUST NOT call Redis. When enabled, only the authenticated availability GET path for a resource/date may use the exact key `resource:available-slots:{resourceId}:{date}`.

#### Scenario: Disabled flag preserves the P0 path

- **WHEN** the cache flag is `false` and an authenticated caller requests available slots
- **THEN** the response is produced by the existing MySQL calculation, no Redis read/write is attempted, and booking correctness is unchanged.

### Requirement: Cache Aside read-through with bounded expiry

When enabled, a cache hit MUST return the stored availability result; a miss MUST calculate from MySQL, write the result with `ttlSeconds = 300 + (uint32(SHA-256(key)[0..3]) mod 601)`, and return the calculated result. The final TTL MUST therefore be deterministic and always inclusive 300..900 seconds (5..15 minutes); no additional jitter may move it outside that interval. Cache population MUST NOT alter booking creation or conflict decisions.

#### Scenario: Hit and miss

- **WHEN** Redis contains the exact key
- **THEN** the cached availability payload is returned without a MySQL calculation.
- **WHEN** the key is absent
- **THEN** MySQL calculation runs, the result is written with the deterministic 300..900-second TTL derived from the exact key, and the same result is returned.

### Requirement: Redis failure is safe and transparent

Any Redis/cache timeout, connection error, serialization error, or write failure MUST be treated as an availability-cache miss/failure and MUST fall back only to the MySQL availability calculation. The capability MUST never decide whether a booking succeeds, and cache failure MUST not alter T07's Redis booking-lock behavior: lock acquisition failure or outage remains fail-closed and MUST NOT be downgraded to DB-only booking.

#### Scenario: Redis outage fallback

- **WHEN** Redis is unavailable during an availability read or cache write
- **THEN** the request still returns the MySQL-calculated availability (subject to normal business errors), no Redis exception escapes as the availability response, and T07's booking Redis lock remains fail-closed while the database booking constraints remain authoritative.

### Requirement: Invalidation occurs only after a committed mutation

After a successful transaction commit, the cache owner MUST invalidate the affected resource/date keys for booking creation, cancellation, approval rejection, and no-show release, and for resource status, time-rule, and closure changes. Upstream owners MUST invoke a cache invalidation port or publish an equivalent after-commit event. No invalidation or cache rebuild may run before commit or after rollback.

#### Scenario: Commit and rollback ordering

- **WHEN** one of the listed mutations commits
- **THEN** its affected key is deleted after commit and a subsequent read recalculates from MySQL.
- **WHEN** the mutation rolls back
- **THEN** no invalidation is observed and the previous cache value remains available.

### Requirement: Optional single-flight cannot reduce safety

The cache package MAY coalesce concurrent misses for one key, but a failed or timed-out single-flight operation MUST release waiters to the MySQL fallback; it MUST not block indefinitely or make a stale/empty value authoritative.

#### Scenario: Stampede guard failure

- **WHEN** a single-flight rebuild fails
- **THEN** all affected requests can independently fall back to MySQL and no booking result is changed.

### Requirement: Cache security and observability

Cache keys and payloads MUST contain no password, complete JWT, database/Redis credential, or unbounded request body. Cache hit/miss/fallback/invalidation metrics or bounded logs MUST be emitted only through approved observability hooks and must not expose sensitive values.

#### Scenario: Sensitive cache input

- **WHEN** a cache diagnostic is emitted
- **THEN** only resource/date identifiers and bounded outcome metadata are present; secrets and full tokens are absent.

### Requirement: Scope and handoff fence

Production and tests for this capability MUST remain under `cache/**` and its tests. Availability, booking, resource, transaction, shared-config, SQL, common, router, HTTP, and deploy changes are handoff requests only; implementation is blocked until committed/reviewed T06/T07/T09/T10 and shared-config owner ports/events are handed off. Reducing or removing this optional cache scope MUST NOT remove T07's booking Redis lock.

#### Scenario: Missing owner handoff

- **WHEN** an upstream owner has not supplied a post-commit invalidation contract
- **THEN** the cache implementation task stops and reports the missing gate instead of editing the upstream package.

