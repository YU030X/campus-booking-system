## Why

Campus booking needs one authoritative resource catalog before slots and bookings can consume it. This change freezes the API, validation, persistence, and authorization contract for categories, resources, weekly availability, and single-day closures.

## What Changes

- Add authenticated category/resource reads and ADMIN-only mutations under the exact `/api/v1` routes frozen in docs/15.
- Define deterministic pagination, DTO/VO envelopes, validation, logical deletion, atomic time-rule replacement, and physical single-day closure deletion.
- Normalize category/resource inputs at the API boundary: trim required names (category 1..50, resource 1..100), default `parentId` and `sortOrder` to decimal-string `"0"`/integer `0`, and apply the documented optional-field blank/null and length rules.
- Clarify global closure scope as path `{id}=0`; resource `id=0` is never a real resource and resource detail `/0` is 404.
- Exclude slots, booking state, SQL/common/auth/frontend changes; production and tests remain in the resource package fence.

## Capabilities

### New Capabilities

- `resource-catalog`: category tree, resource catalog, weekly time rules, and closure APIs.

### Modified Capabilities

- None.

## Impact

- Production: `booking-api/src/main/java/com/yu030x/booking/resource/**`; tests: `booking-api/src/test/java/com/yu030x/booking/resource/**` only.
- Requires foundation merge and T02 ADMIN/STUDENT authorities. Coding may proceed with test-only mock principals; no production fixture. Rebase/merge T02 before endpoint-security acceptance and PR ready.
- Validate with real MySQL 8, `mvn verify`, `openspec validate ... --strict`, diff-check, spec sync, and downstream T05/T06 contract handoff.
