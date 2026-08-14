## 1. Domain and persistence

- [x] 1.1 Define category/resource/time_rule logical-delete entities and physical resource_closure entity exactly matching docs/11 nullable/timestamp fields; keep code under `booking-api/src/main/java/com/yu030x/booking/resource/**`.
- [x] 1.2 Add mappers for tree, filters, detail, stable ordering, active/deleted checks, rule replacement, row-lock, and closure unique/scope queries; no SQL files.

## 2. DTO/VO and validation

- [x] 2.1 Implement exact request DTOs and VOs; canonical Result/PageResult; Long response IDs strings; no deleted fields; decimal string path/query accepted; normalize category/resource trims, defaults, blank-to-null optionals, description preservation, and documented length/range boundaries.
- [x] 2.2 Validate category tree, resource fields/capacity/status/policies, pageNumber/pageSize/filters, and time-rule HH:mm:ss 00/30 constraints; tests must cover normalization/defaults success and every documented boundary overrun as HTTP 400 code 40000 with `data:null`.
- [x] 2.3 Validate closure body/date and `{id}=0` global semantics; duplicate unique conflict 409; wrong-scope delete 404.

## 3. Services and transactions

- [x] 3.1 Implement category tree and logical delete (children/active refs =>409).
- [x] 3.2 Implement resource list/detail/create/update/status with stable sort and logical-delete exclusion; id 0 detail =>404.
- [x] 3.3 Implement atomic time-rule replacement: lock resource row, logical-delete active rules, insert new set; empty array allowed; concurrent MySQL8 serial-order/no-mixed-row test.
- [x] 3.4 Implement nested closure create/delete, physical delete, scope verification, and global/resource same-date coexistence.

## 4. HTTP/security

- [x] 4.1 Add controllers for every exact `/api/v1` route in spec; no top-level closures; canonical envelopes/errors.
- [x] 4.2 Require authentication on reads and T02 ADMIN on writes. Test-only mock principals allowed; no production fixture. Rebase/merge T02 before endpoint security acceptance and PR ready.

## 5. Verification and handoff

- [x] 5.1 Add resource-package unit/API security, validation, pagination, tree, status, deletion, exact-route, normalization/defaults, and boundary-overrun (400/40000/data:null) tests.
- [x] 5.2 Add time-rule overlap/boundary/atomic/concurrency tests and closure duplicate/scope/physical-delete tests.
- [x] 5.3 Run real MySQL8 integration tests, `mvn verify`, `git diff --check`; record exact results.
- [x] 5.4 Rebase T02, rerun suite, strict validate, sync specs, validate main specs, and provide T05/T06 contract handoff; keep PR Draft until gate passes.
