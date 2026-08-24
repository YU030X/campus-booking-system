# resource-availability Specification

## Purpose
Define the authenticated, read-only availability view that turns a resource's weekly rules, closures, current occupancy, and date limits into deterministic 30-minute choices for students and the downstream booking flow.

## Requirements

### Requirement: Availability route and envelope

The service MUST expose authenticated `GET /api/v1/resources/{id}/available-slots?date=yyyy-MM-dd`. The `date` query parameter is required and MUST be parsed strictly as an ISO calendar date. Success MUST use the canonical `Result` envelope; missing or logically deleted resources MUST return HTTP 404 with code `40400` and `data:null`, and unauthenticated callers MUST be rejected by the shared authentication contract.

#### Scenario: Authenticated date query

- **WHEN** an authenticated caller requests a valid resource ID and `date=2026-10-15`
- **THEN** the service returns HTTP 200 with the canonical success envelope and the availability payload.

#### Scenario: Invalid date or missing resource

- **WHEN** the date is absent, malformed, or the resource does not exist/is logically deleted
- **THEN** the service returns HTTP 400/40000 for the invalid date, or HTTP 404/40400 for the missing/deleted resource, with `data:null`.

### Requirement: Frozen availability payload and slot boundaries

The payload MUST contain `resourceId` as a decimal string, `date` as `yyyy-MM-dd`, `slotMinutes` equal to `30`, and `slots` ordered by `startTime`. Each slot MUST contain `startTime`, `endTime` (`HH:mm`), and boolean `available`. Slots MUST use Asia/Shanghai local time, be aligned to `:00` or `:30`, represent left-closed/right-open `[startTime,endTime)` intervals, and never cross the requested calendar day. An open interval's end MUST NOT become a returned slot start.

#### Scenario: Half-open interval splitting

- **WHEN** an active rule is `[08:00,10:00)`
- **THEN** the payload contains `08:00-08:30`, `08:30-09:00`, `09:00-09:30`, and `09:30-10:00`, and contains no `10:00` slot.

#### Scenario: Multiple open intervals

- **WHEN** a day has `[08:00,10:00)` and `[13:00,15:00)` rules
- **THEN** the payload contains both ordered ranges and no slots in the closed gap; duplicate starts are not emitted when adjacent rules meet.

### Requirement: Date window and past-slot filtering

The service MUST evaluate dates using `Asia/Shanghai`. A requested date before the current local date, or after `today + maxAdvanceDays` inclusive for the resource, MUST return HTTP 400 with code `40000` and `data:null`. For the current local date, slots whose local start instant is not strictly after the evaluation instant MUST be omitted; future-date slots MUST not be removed merely because their local time is earlier than the current clock time.

#### Scenario: Same-day past filtering

- **WHEN** the current Asia/Shanghai time is `2026-10-15 09:15` and rules include `09:00-11:00`
- **THEN** `09:00-09:30` is omitted while `09:30-10:00` and later future slots remain.

#### Scenario: Advance-window boundary

- **WHEN** a resource has `maxAdvanceDays=3`
- **THEN** today and today plus three days are eligible, while today minus one day and today plus four days are rejected with HTTP 400/40000.

### Requirement: Resource state and closure handling

Only resources with status `1` (available) may produce availability. A missing/deleted resource MUST be 404/40400. A resource with status `0` (stopped) or `2` (maintenance) MUST return HTTP 409 with resource business code `42000` and `data:null`. A global closure row (`resource_id=0`) or a resource closure row for the requested date MUST produce a successful payload with an empty `slots` array and no occupied-slot writes.

#### Scenario: Closed date

- **WHEN** either a global or resource-specific closure exists for the requested date
- **THEN** the service returns HTTP 200 with the requested resource/date metadata, `slotMinutes:30`, and `slots:[]`.

#### Scenario: Unavailable resource state

- **WHEN** the resource is stopped or under maintenance
- **THEN** the service returns HTTP 409/42000 with `data:null` and does not calculate or persist slots.

### Requirement: Occupied slots are read-only availability inputs

For an eligible open date, every `booking_slot` row matching the resource and a 30-minute start on that local date MUST mark the corresponding returned slot `available:false`; unmatched generated slots MUST be `true`. The endpoint MUST perform no booking creation, no `booking_slot` insertion/deletion, and no change to the `(resource_id,slot_time)` unique index. Cache contents MUST NOT be treated as the correctness source for this response or for later booking success.

#### Scenario: Existing occupancy

- **WHEN** `booking_slot` contains the resource's `09:00` row and the open rule includes `[08:00,10:00)`
- **THEN** the `09:00-09:30` slot is returned with `available:false`, the other generated slots remain available, and the database occupancy row is unchanged.
