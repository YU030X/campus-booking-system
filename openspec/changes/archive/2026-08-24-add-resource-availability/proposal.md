## Why

Students and the later booking flow need one deterministic view of which 30-minute periods can be requested for a resource on a given day. T03 now owns the resource, weekly-rule, and closure data, but without a read-only availability contract clients cannot consistently apply same-day, closure, status, or existing-occupancy rules before handing a selection to T07.

## What Changes

- Add authenticated `GET /api/v1/resources/{id}/available-slots?date=yyyy-MM-dd`.
- Freeze the response as a canonical result containing `resourceId` (string), `date`, `slotMinutes: 30`, and `slots[]` with `startTime`, `endTime`, and `available`.
- Compute slots in `Asia/Shanghai` from active weekly rules, supporting multiple same-day open intervals, half-open `[startTime,endTime)` boundaries, and `:00`/`:30` alignment.
- Treat global (`resource_id=0`) or resource-specific closures as a closed day; filter past slots for the current date; enforce the resource's inclusive `maxAdvanceDays` window.
- Exclude missing/deleted resources and non-available resource states according to the shared error contract; read current `booking_slot` rows as occupancy.
- Keep the endpoint read-only: no booking creation, no `booking_slot` index changes, and no cache result as a source of booking correctness.
- Add pure-function coverage for slot generation/filtering and real MySQL 8 integration coverage for resource state, rules, closures, and occupied slots.

## Capabilities

### New Capabilities

- `resource-availability`: authenticated date-scoped availability calculation and response contract.

### Modified Capabilities

- `resource-catalog`: extend the frozen authenticated resource route matrix with the nested `available-slots` read route while retaining the catalog scope fence and existing resource/closure contracts.

## Impact

- Production and tests are limited to the T06 ownership fence: `booking-api/src/main/java/com/yu030x/booking/availability/**`, its pure time-slot functions, and corresponding tests. T03 resource mappers/entities and shared contracts remain read-only dependencies.
- Requires the T03 merge gate before implementation validation: active resource, time-rule, closure, and `booking_slot` schemas/queries must match the frozen contracts.
- T07 consumes the frozen DTO and pure calculation behavior for booking validation; this change does not create bookings, write slots, alter the unique index, or introduce cache-based correctness.
- Validation will use real MySQL 8 integration tests, pure-function unit tests, `mvn verify`, `openspec validate add-resource-availability --type change --strict`, and `git diff --check`; no commit or push is part of this planning change.
