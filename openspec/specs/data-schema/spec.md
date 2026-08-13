# Data Schema Specification

## Purpose

Defines the frozen booking database structure, deterministic migrations, and lifecycle invariants required for compatible application behavior.

## Requirements

### Requirement: Frozen twelve-table baseline
V001–V005 hand-authored SQL MUST create exactly `user`, `resource_category`, `resource`, `resource_time_rule`, `resource_closure`, `booking`, `booking_slot`, `approval_record`, `violation_record`, `blacklist`, `notification`, and `operation_log`. All tables MUST be InnoDB/utf8mb4 with no physical foreign keys. `password` MUST be `VARCHAR(100)`. Logical-delete columns and defaults are part of the frozen baseline.

#### Scenario: Baseline
- **WHEN** migrations complete
- **THEN** twelve tables exist.

### Requirement: Required keys and indexes
The schema MUST match docs/11 exact DDL fields/defaults/indexes for all twelve tables: `booking` indexes on `(user_id,status)`, `(resource_id,start_time)`, `(status,start_time)`; `booking_slot` unique `(resource_id,slot_time)`; `violation_record` unique `uk_booking_type(booking_id,violation_type)`; `blacklist` fields `start_date`,`end_date` and `idx_user_end(user_id,end_date)`; `notification` index `idx_user_read_time(user_id,is_read,created_at)`; and `operation_log` docs/11 fields (no invented operator/target) with `idx_user_time(user_id,created_at)` and `idx_created_at(created_at)`. Active blacklist queries MUST require `start_date <= today AND end_date >= today`.

#### Scenario: Indexes
- **WHEN** metadata is queried
- **THEN** each listed index exists and matches docs/11 names and columns.

#### Scenario: Schema validation
- **WHEN** V001-V005 run on fresh databases
- **THEN** exactly twelve InnoDB/utf8mb4 tables and all docs/11 fields/defaults/indexes exist with no physical foreign keys; V005 contains no business seed and only an executable post-seed placeholder comment.

### Requirement: Deterministic fresh-database execution
V001–V005 MUST be applied in order to each of two independent empty MySQL 8.0 databases and both MUST yield identical table/index definitions; rerunning in the same database is not required.

#### Scenario: Fresh comparison
- **WHEN** snapshots are compared
- **THEN** definitions match.

### Requirement: Slot release and lifecycle invariants
`booking_slot` MUST enforce `(resource_id,slot_time)` uniqueness and 30-minute `[start,end)` alignment. `CANCELLED`, `REJECTED`, and `NO_SHOW` MUST release slot rows in the same transaction; if a P1 completion task is implemented, it MUST physically release slots. `COMPLETED` MUST NOT depend on automatic completion. Blacklist `start_date`/`end_date` semantics are explicit: a record is active only when `start_date <= today AND end_date >= today`; violation idempotency is unique. Score deduction/query transactions are deferred to business changes.

#### Scenario: Release
- **WHEN** terminal release status occurs
- **THEN** slots are physically deleted in the same transaction.
