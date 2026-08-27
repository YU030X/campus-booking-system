## Purpose

Define deterministic empty-database, backup/restore, persistence, failure, and rollback evidence for the booking runtime.

## ADDED Requirements

### Requirement: Empty migration baseline
V001–V005 MUST run on a fresh MySQL 8 database in order and produce exactly the twelve frozen tables with no seed rows. T13 MUST never edit existing migrations; demo data is supplied only by a separately owned seed change or runtime fixture.

#### Scenario: Fresh schema audit
- **WHEN** an empty database applies V001–V005 and metadata is inspected
- **THEN** exactly twelve InnoDB/utf8mb4 tables and the documented indexes exist, no business seed is present, and a second fresh database yields the same definitions.

### Requirement: Backup and restore proof
The runbook MUST specify a consistent MySQL backup, restore into an isolated target, verification of schema and representative booking/slot rows, and an explicit operator-recorded RPO/RTO assumption.

#### Scenario: Restore validation
- **WHEN** a backup is restored into an empty isolated database
- **THEN** schema, booking lifecycle data, and slot uniqueness match the source evidence, restore duration is recorded against the stated RTO, and the maximum acceptable data loss is recorded as RPO.

### Requirement: Volume restart persistence
The verification MUST recreate or restart API, MySQL, and Redis containers and prove that MySQL data and the frozen consumer-specific Redis behavior survive the declared restart scope. Redis loss MUST NOT compromise database uniqueness or authorization correctness.

#### Scenario: T07 booking-lock Redis failure
- **WHEN** volumes are retained while services restart and Redis is stopped during a T07 booking-lock attempt
- **THEN** persisted MySQL records remain, health/order recovery is observable, and the application fails closed with HTTP 409, code `43000`, message/category `SYSTEM_BUSY`, without a DB-only fallback or booking mutation.

#### Scenario: T12 availability/cache Redis failure
- **WHEN** Redis is unavailable during a T12 availability/cache read
- **THEN** the application MAY fall back to MySQL, records the fallback latency and consistency evidence, and preserves authorization and database uniqueness.

### Requirement: Rollback and recovery boundaries
The runbook MUST describe image/config rollback, migration rollback limits, backup selection, health verification, operator stop conditions, and recovery evidence. It MUST not imply destructive rollback of committed migrations without an owner-approved recovery plan.

#### Scenario: Failed release recovery
- **WHEN** a deployment health gate fails after release
- **THEN** the operator can select the prior pinned image/config, restore service health, preserve persistent data, and record the incident and remaining risk without silently changing schema.
