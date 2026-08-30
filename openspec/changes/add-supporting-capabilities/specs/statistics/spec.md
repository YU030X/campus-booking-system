## Purpose

Define an optional, privacy-preserving administrator statistics read model using existing MySQL tables and indexes only.

## ADDED Requirements

### Requirement: Independent statistics flag and exact administrator routes

The statistics capability MUST have an independent shared-config feature flag defaulting to `false`. When enabled it MUST expose ADMIN-only `GET /api/v1/admin/statistics/resources` and `GET /api/v1/admin/statistics/bookings`; when disabled the endpoints remain unavailable through the existing protected behavior. No student or unauthenticated caller may access either route.

#### Scenario: Role boundary

- **WHEN** a STUDENT or unauthenticated caller requests either statistics route
- **THEN** the canonical 401/403 response is returned and no aggregate query is exposed.

### Requirement: Bounded date range

Both routes MUST require an inclusive `fromDate` and `toDate` in `yyyy-MM-dd`, reject `fromDate > toDate`, and reject ranges longer than 366 calendar days with canonical 400/40000. The query timezone is Asia/Shanghai and no implicit unbounded default range is allowed.

#### Scenario: Date validation

- **WHEN** a caller supplies a reversed, malformed, or 367-day range
- **THEN** the API returns HTTP 400/code 40000 with `data:null` and executes no aggregate query.

### Requirement: Exact grouped aggregate DTOs without PII

The resources response data MUST contain `{fromDate,toDate,records}` where each record is `ResourceUsageAggregate {resourceId,resourceName,bookingCount,completedCount,cancelledCount,noShowCount,occupiedSlotMinutes,usageRate}`; `resourceId` is a JSON string, counts/minutes are non-negative integers, and `usageRate` MUST be the bounded decimal ratio `occupiedSlotMinutes / schedulableMinutes`. `schedulableMinutes` is computed in Asia/Shanghai by summing, for every date in the inclusive range, the minutes made schedulable by that day's `resource_time_rule`; a closure day contributes zero, and the daily values are summed across the range. When the summed denominator is zero, `usageRate` MUST be null. `occupiedSlotMinutes` MUST use the frozen booking/slot occupancy semantics. The bookings response data MUST contain `{fromDate,toDate,records}` where each record is `BookingStatusAggregate {status,count}` using only the frozen booking statuses. Neither response may include user IDs, names, phone numbers, purposes, or raw booking rows.

#### Scenario: Aggregate shape

- **WHEN** an ADMIN requests a valid range
- **THEN** records are grouped by resource or booking status, use the exact fields above, are deterministically ordered (`resourceId` ascending for resources and frozen status order for bookings), and contain no raw PII.

### Requirement: MySQL and index-safe query evidence

Statistics queries MUST use existing schema/indexes only and MUST not add or edit SQL migrations. Implementation acceptance MUST run MySQL 8 `EXPLAIN` for both aggregate query families, record the plans, and stop with an index-request handoff if a query is unacceptably slow; it MUST not weaken authorization or widen the date range to hide performance problems.

#### Scenario: Slow plan

- **WHEN** `EXPLAIN` shows a missing or unsuitable access path
- **THEN** the task records a concrete index request for the SQL owner and does not edit `sql/**` in T12.

### Requirement: Scope and handoff fence

Production/tests MUST remain under `statistics/**` and the agreed P1 frontend statistics directories. No schema, pom, config, common, booking/resource/availability/user, router, HTTP, or deploy files may be edited; route/security wiring is a handoff to the shared owners.

#### Scenario: Shared-file request

- **WHEN** a statistics implementation needs a router or configuration edit
- **THEN** it stops and records the owner handoff instead of editing the shared file.
