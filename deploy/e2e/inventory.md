# T13 Integration/E2E Coverage Inventory

Basis: actual repository state on this branch (paths relative to
`booking-api/src/test/java/com/yu030x/booking/`). This inventory is mirrored by
the hardcoded class list in `deploy/e2e/run.ps1` (ApiIntegration mode). Any
class present in run.ps1 but missing on disk BLOCKS execution (exit 3) —
coverage is never silently claimed.

Legend: [UNIT] plain unit · [MVC] standalone MockMvc · [SLICE] @WebMvcTest ·
[CTX] Spring context, no DB · [IT-M] real MySQL · [IT-MR] real MySQL+Redis ·
[IT-R] real Redis.

## Requirement → class mapping (ApiIntegration required set)

| Requirement slice | Classes (file:class) |
|---|---|
| auth / login / register / password non-disclosure | auth/AuthServiceTest.java:AuthServiceTest [UNIT] (BCrypt-12, no credential fields, dummy-hash anti-enumeration) · auth/AuthControllerMockMvcTest.java:AuthControllerMockMvcTest [MVC] (201 envelope w/o token/internal fields) · auth/RequestValidationTest.java:RequestValidationTest [UNIT] · auth/security/JwtSecurityTest.java:JwtSecurityTest [UNIT] (secret/TTL bounds, tamper, disabled-user rejection) |
| resources / availability | resource/ResourceApiTest.java:ResourceApiTest [SLICE] · availability/AvailabilityApiTest.java:AvailabilityApiTest [SLICE] (401 canonical, frozen shape) · availability/AvailabilityServiceTest.java:AvailabilityServiceTest [UNIT] |
| direct + pending booking creation | booking/BookingControllerMockMvcTest.java:BookingControllerMockMvcTest [MVC] · booking/BookingCreatorTest.java:BookingCreatorTest [UNIT] (atomic booking+slots, need_approval→PENDING_APPROVAL, duplicate-key→conflict) · booking/BookingCreationGuardTest.java:BookingCreationGuardTest [UNIT] · booking/BookingIntervalValidatorTest.java:BookingIntervalValidatorTest [UNIT] · booking/BookingSlotSplitterTest.java:BookingSlotSplitterTest [UNIT] |
| approval / reject | approval/ApprovalServiceTest.java:ApprovalServiceTest [UNIT] · approval/ApprovalRequestTest.java:ApprovalRequestTest [UNIT] · approval/ApprovalControllerMockMvcTest.java:ApprovalControllerMockMvcTest [MVC] |
| cancellation | ApprovalServiceTest (2h late-cancel boundary) · ApprovalControllerMockMvcTest (winner + duplicate identical, foreign 404) |
| check-in | checkin/CheckInServiceTest.java:CheckInServiceTest [UNIT] (±15min window, idempotent, foreign 404) · checkin/CheckInControllerMockMvcTest.java:CheckInControllerMockMvcTest [MVC] |
| no-show / violation | violation/ViolationServiceTest.java:ViolationServiceTest [UNIT] · violation/DefaultViolationPortTest.java:DefaultViolationPortTest [UNIT] · violation/ViolationControllerMockMvcTest.java:ViolationControllerMockMvcTest [MVC] · task/NoShowScanTaskTest.java:NoShowScanTaskTest [UNIT] · task/NoShowItemProcessorTest.java:NoShowItemProcessorTest [UNIT] |
| slot release (REJECTED/CANCELLED/NO_SHOW) | booking/DefaultBookingActionsTest.java:DefaultBookingActionsTest [UNIT] (release-once semantics for all three terminal states) · approval/ApprovalMysqlIntegrationTest.java:ApprovalMysqlIntegrationTest [IT-M] (reject releases atomically) · violation/NoShowMysqlIntegrationTest.java:NoShowMysqlIntegrationTest [IT-M] (atomic release w/ violation) · booking/BookingActionsMysqlIntegrationTest.java:BookingActionsMysqlIntegrationTest [IT-M] |
| idempotency | DefaultBookingActionsTest · ApprovalMysqlIntegrationTest (repeat + concurrent duplicates) · BookingActionsMysqlIntegrationTest · CheckInServiceTest · DefaultViolationPortTest · NoShowMysqlIntegrationTest · violation/ViolationPortLateCancelMysqlIntegrationTest.java:ViolationPortLateCancelMysqlIntegrationTest [IT-M] |
| concurrency (same-slot 409, 1 winner) | booking/BookingConcurrencyIntegrationTest.java:BookingConcurrencyIntegrationTest [IT-MR] (60 concurrent → 1 winner; distinct resources/dates not globally serialized) · booking/BookingRedisLockIntegrationTest.java:BookingRedisLockIntegrationTest [IT-R] (fail-closed busy) · booking/BookingMysqlIntegrationTest.java:BookingMysqlIntegrationTest [IT-M] (frozen unique key rollback) |
| boundaries (401/403/404, expired/missing/malformed credentials, cross-owner no-mutation) | approval/ApprovalApiRealIntegrationTest.java:ApprovalApiRealIntegrationTest [IT-MR] (real security chain; student cancel masks foreign rows and releases slots — the foreign-booking mutation path is exercised only through the owner's own booking) · common/config/SecurityContextIntegrationTest.java:SecurityContextIntegrationTest [CTX] (health public, others denied) · auth/security/JwtSecurityTest.java:JwtSecurityTest [UNIT] (missing-claim rejection incl. `exp`; **expired token rejection**: exp >30s in the past ⇒ 401, ≤30s skew grace — JwtSecurityTest.java:97-103; malformed/tampered/duplicated headers; disabled/deleted-user rejection) · user/UserMysqlIntegrationTest.java:UserMysqlIntegrationTest [IT-M] (register race → 1 user + 1×409) |

Cross-owner "no unauthorized mutation" evidence is exactly the no-side-effect
assertions inside these classes: ApprovalServiceTest/ApprovalMysqlIntegrationTest
(foreign cancel → uniform 404, zero state change), CheckInServiceTest (foreign
404, no effects), NotificationServiceTest (foreign → identical 404),
ApprovalApiRealIntegrationTest (slot release only via the owner's own cancel).
Where a test asserts masking but not a full row-state diff, this inventory says
"masking" — nothing stronger.

## Additional required integration classes (slice-fix)

| Class | Why required |
|---|---|
| resource/ResourceMysqlIntegrationTest.java:ResourceMysqlIntegrationTest [IT-M] | concurrent time-rule replacement; closure scope/unique constraints on real MySQL |
| availability/AvailabilityMysqlIntegrationTest.java:AvailabilityMysqlIntegrationTest [IT-M] | persisted rules/closures/occupancy reads on real schema |
| user/UserCreditMysqlIntegrationTest.java:UserCreditMysqlIntegrationTest [IT-M] | atomic deduction, 0-floor clamp, concurrent no-lost-update |
| common/config/RedisRealIntegrationTest.java:RedisRealIntegrationTest [IT-R] | real Redis: health, StringCodec, watchdog 30s, lock roundtrip, clean shutdown |

Env gates honored as authored by the test owners: `RESOURCE_MYSQL_URL`,
`USER_CREDIT_MYSQL_URL`, `DB_URL/DB_USERNAME/DB_PASSWORD`, `REDIS_HOST/PORT/
PASSWORD`, `BOOKING_MYSQL8_TEST=true` (set by run.ps1 for this lane).

## Browser coverage (StudentBrowser — T08 harness, `scripts/tests/t08/qa-harness.mjs` cases 01–15)

Registration/login UI · XSS-neutralizing query handoff · availability guards ·
disabled-resource chips · submit dedup + 201 · real 409/43000 conflict +
refresh + recovery · max-active guard loop · pagination/status filter ·
detail 14-field + 7-state timeline · UI cancel incl. **server-side slot
release check** · unsafe-id zero-transport + unknown 404 · 401 session
clearing · 403 session preservation. Runner: Chrome `--headless=new` raw CDP.

## COMPLETED release

Optional automatic-completion feature is NOT enabled and NOT required by any
P1 gate; no class in the required set exercises COMPLETED release. Explicitly
out of scope here (contract: tasks 2.2 wording).

## Known coverage gaps (honest, with owner requests)

1. **Approval browser flow (deterministic)** — no automated browser case for
   admin approve/reject UI today; T08 cases cover student flows only.
   → OCR-8 (new): owner-approved deterministic fixture + approved command.
2. **Browser route-refresh after EVERY state transition** — T08 covers refresh
   after conflict/cancel; a full per-state (approve→refresh, check-in→refresh,
   no-show→refresh) refresh matrix is not automated. API-level equivalents are
   covered (see approval/check-in rows). → deferred to OCR-8 scope.
3. **E2E evidence for admin reads (pending page via real admin login)** —
   covered at MVC/IT level (DefaultBookingAdminReadsTest, ApprovalApiRealIntegrationTest);
   no browser case. Same OCR-8 scope.
4. **Notification/statistics browser flows** — out of T13 slice scope; covered
   at unit/IT level by their owners (notification/statistics packages).

Nothing in this inventory claims coverage beyond the classes listed; any
absence above is a gap by construction, not an omission of this document.
