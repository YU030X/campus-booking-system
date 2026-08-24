## Context

T03 supplies the frozen resource, weekly `resource_time_rule`, closure, and resource-state data. The new endpoint is a read-only consumer of those records plus current `booking_slot` occupancy; it must fit the shared `Result`/error/time contracts without taking ownership of T03 entities or T07 booking writes.

## Goals / Non-Goals

**Goals:**

- Isolate availability orchestration and pure slot calculation under `booking-api/.../availability/**`.
- Make Asia/Shanghai date-window and same-day past filtering deterministic through an injectable clock.
- Keep the response contract stable for T07 and later frontend consumers, including string resource IDs and `slotMinutes:30`.
- Validate persisted behavior against a real MySQL 8 schema and occupancy rows.

**Non-Goals:**

- No booking creation, booking state transition, slot insertion/deletion, or unique-index migration.
- No edits to T03 resource entities/mappers, shared envelope/auth code, SQL migrations, frontend code, or cache infrastructure.
- No Redis/cache read path; availability is advisory and never the booking correctness source.

## Decisions

1. **Controller and service boundary.** Add one authenticated controller for the frozen nested route and an application service that validates the date/window/state, reads T03-owned data through read-only ports/adapters, queries occupied starts, and maps the pure result to the canonical VO. This keeps HTTP concerns out of the calculation and avoids changing T03's ownership tree.

2. **Pure calculation first.** Represent each active rule as a local half-open interval and generate 30-minute starts with `current < end`. Union starts in a sorted set so adjacent or accidentally duplicated rule rows cannot duplicate a slot; retain the interval's end only as the exclusive boundary. Apply the injected `Asia/Shanghai` clock to remove starts that are not strictly after `now` only when the requested date is today, then apply the occupied-start set to set `available`.

3. **Date and state gates before slot work.** Parse strict `LocalDate`, obtain `today` from the injected zone-aware clock, reject dates before today or beyond inclusive `today.plusDays(maxAdvanceDays)`, load the resource, reject missing/deleted with 404, reject status 0/2 with 409/42000, and check both closure scopes. A closed date returns metadata with an empty list and does not query or write booking slots.

4. **MySQL occupancy read.** For an open eligible date, query `booking_slot` by `(resource_id, slot_time)` over the local `[dayStart, nextDayStart)` DATETIME range and convert the starts to a set. Do not infer occupancy from `booking` joins, cache entries, or an altered index; the frozen `(resource_id,slot_time)` key remains the source of current slot occupancy.

5. **Contract and serialization.** Keep `resourceId` a decimal string, `date` as `yyyy-MM-dd`, and slot times as `HH:mm` local values inside the response payload. Reuse the shared error mapping and authentication boundary. Do not add a new time zone or UTC conversion layer for this single-campus system.

6. **Verification and handoff.** Unit tests will exercise the pure function with a fixed clock and interval/closure/occupancy matrices. MySQL 8 integration tests will use the real migrations and persisted T03 fixtures for status, logical deletion, global/resource closures, max-advance dates, multiple rules, and `booking_slot` rows. The final handoff records the DTO and pure-function behavior for T07; T07 must still revalidate and rely on the database unique index when creating a booking.

## T07 handoff (T06 implementation evidence)

`AvailabilityService#get` is a read-only boundary: it returns `AvailabilityVO` with decimal-string `resourceId`, the requested local `date`, `slotMinutes=30`, and ordered `HH:mm` half-open slots. `AvailabilityCalculator#calculate` accepts `(date, today, now, maxAdvanceDays, intervals)`, rejects dates outside the inclusive window and malformed intervals, removes same-day starts not strictly after `now`, and deduplicates starts in sorted order. Missing/deleted resources map to 404, status 0/2 to 409, and closed dates return metadata with no slots. T07 must revalidate all booking constraints and perform transactional `booking_slot` insertion relying on the frozen `uk_resource_slot(resource_id,slot_time)` unique index; this availability read is advisory and does not reserve a slot.

## Risks / Trade-offs

- **[Read/booking race]** A slot can become occupied after this response → document the advisory nature and require T07's transactional insert plus the existing unique index.
- **[Clock-boundary flakiness]** A slot can cross from future to past during a test → inject a fixed `Clock` and assert exact boundary instants in unit tests.
- **[Rule duplication or adjacency]** Multiple T03 rows can produce repeated starts → normalize to a sorted set and cover adjacent/multi-interval cases.
- **[T03 contract drift]** Resource/closure fields or status semantics may differ before merge → block implementation acceptance on the T03 merged gate and stop rather than editing shared files.
- **[Unavailable-state UX]** Stopped/maintenance resources do not have a meaningful slot list → use the shared 409/42000 conflict response consistently and keep missing/deleted as 404/40400.

## Migration Plan

No database or cache migration is required. After the T03 merge gate, implement and validate the endpoint; rollback is removal/reversion of the availability package and route without touching existing tables or indexes. The planning branch itself is not committed or pushed.
