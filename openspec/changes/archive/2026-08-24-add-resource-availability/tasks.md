## 1. Preconditions and ownership

- [x] 1.1 Confirm the T03 `add-resource-catalog` change is merged at the implementation base and that resource status/deleted, weekly rules, closure scopes, canonical errors, and `booking_slot` schema match the frozen contracts; stop and report if the gate is not satisfied.
- [x] 1.2 Create only the availability production/test paths under `booking-api/src/main/java/com/yu030x/booking/availability/**` and `booking-api/src/test/java/com/yu030x/booking/availability/**`; record any required shared-file change request instead of editing T03/shared/SQL files.

## 2. Pure time-slot calculation

- [x] 2.1 Define the availability DTO/VO contract: decimal-string `resourceId`, `date`, `slotMinutes:30`, and ordered `slots` with `startTime`, `endTime`, and `available`; preserve canonical Result/error serialization.
- [x] 2.2 Implement a clock-injected pure calculator for Asia/Shanghai that validates inclusive `maxAdvanceDays`, rejects past dates, removes same-day starts not strictly after `now`, and leaves future-date local times intact.
- [x] 2.3 Generate 30-minute `[start,end)` slots from one or more `00/30`-aligned open intervals, exclude each interval end, keep same-day ordering, and deduplicate starts across adjacent/overlapping inputs.
- [x] 2.4 Add pure-function unit tests for 00/30 acceptance, 15/45 rejection, left-closed/right-open boundaries, multi-interval gaps, adjacent rules, same-day past filtering, future dates, and max-advance inclusive/exclusive edges.

## 3. Read-only API orchestration

- [x] 3.1 Add the authenticated `GET /api/v1/resources/{id}/available-slots?date=yyyy-MM-dd` controller and strict date parsing with shared 400/404/409 envelopes.
- [x] 3.2 Read T03-owned resource data through a read-only adapter/port; enforce missing/deleted 404/40400 and status `0|2` 409/42000 before calculating slots.
- [x] 3.3 Check both global (`resource_id=0`) and resource-specific closures; return metadata plus an empty slot list for a closed date without querying or writing occupancy rows.
- [x] 3.4 Query current-day `booking_slot` rows for the local Asia/Shanghai `[dayStart,nextDayStart)` range and mark matching generated starts unavailable; perform no booking writes, index changes, or cache-based decisions.
- [x] 3.5 Add endpoint/API + service tests for authentication, payload shape, invalid dates, missing/deleted resources, stopped/maintenance resources, global/resource closures, multiple rules, past filtering, null persisted lists, invalid persisted rules, and occupied-slot flags.

## 4. Real MySQL 8 verification

- [x] 4.1 Run the availability integration suite against a real MySQL 8 database using the frozen migrations and T03 fixtures; cover logical deletion, statuses, maxAdvanceDays, closure scope, rule gaps, and persisted `booking_slot` occupancy.
- [x] 4.2 Assert the endpoint leaves `booking_slot` rows and the `(resource_id,slot_time)` unique index unchanged, and document the advisory read/booking race for T07.

## 5. Handoff and gates

- [x] 5.1 Publish the frozen DTO, pure-calculator inputs/outputs, error semantics, and read-only boundary as the T07 handoff; explicitly require T07 to revalidate and rely on the MySQL unique index when creating bookings. The handoff is recorded in design.md; real-MySQL acceptance remains pending in 4.1/4.2 and the remaining gates.
- [x] 5.2 Run the narrow availability tests, real MySQL 8 integration tests, `mvn verify`, `openspec validate add-resource-availability --type change --strict`, and `git diff --check`; record exact results and stop on any failure.
- [x] 5.3 Recheck the diff is limited to availability artifacts/code/tests, leave implementation uncommitted and unpushed for review, and do not begin T07 or cache work from this change.

## Acceptance evidence (2026-08-25)

- After final style and scope review, the official MySQL 8.0.40 migrations/table-count gate passed in a fresh run.
- `AvailabilityMysqlIntegrationTest`: 2/2; `Availability*`: 20/20; `mvn verify`: 72/72; failures/errors/skipped=0.
- Test sources assert `booking_slot` rows and `uk_resource_slot` index `index_name`/`non_unique`/`seq`/`column` are unchanged before and after, covering statuses, logical deletion, maxAdvance, global/resource closures, rule gaps, and occupancy.
- `openspec validate --archived --strict --no-interactive`: 8/8 valid after archive. `git diff --check`: exit 0, no output.
- Cleanup verified no LISTEN, no mysqld, and no generated DataDir.
- T06-owned files only availability production/tests plus this change artifacts. Four staged docs were pre-existing unrelated user changes before entering the worktree; not modified or included in T06 scope.
- T07/cache work not started.
