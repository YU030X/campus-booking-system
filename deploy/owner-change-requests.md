# T13 Owner Change Requests & External Blockers

Standing rule: T13 never edits business/frontend/common source, Maven/npm
manifests, or `sql/` migrations. Every defect below is a REQUEST to the owning
task; T13 only records evidence and blocks its own gates accordingly.

## OCR-1 (T12): availability cache read-path wiring

Status: RESOLVED IN OWNER BRANCH — live T13 outage execution remains not run.

Evidence:

* The cache port exists and is conditional:
  * `booking-api/src/main/java/com/yu030x/booking/cache/config/AvailabilityCacheConfiguration.java:19` —
    `@ConditionalOnProperty(name = "booking.cache.enabled", havingValue = "true", matchIfMissing = false)`
  * same file, line 23: `availabilityCachePort(ObjectProvider<RedissonClient>)`
    bean defined; line 29 shows a second bean injecting `AvailabilityCachePort`.
* The merged read path now consumes it:
  * `AvailabilityService.java` injects `ObjectProvider<AvailabilityCachePort>`,
    calls `cache.read`, falls back to the existing MySQL calculation on MISS or
    FAILURE, and best-effort writes the result.
  * T12 `HANDOFF.md` records narrow cache 29/29 and real MySQL/Redis 5/5,
    including live fallback and unchanged T07 fail-closed behavior.
* `redis-failure-check.ps1` now always performs the strict live T12 observation;
  no owner-attestation bypass remains. A Compose outage run is still required
  before task 5.4 can pass.

## OCR-2 (external/user): public acceptance inputs missing

Blocks tasks 8.1–8.3 by design. Missing: authorized target host/domain, DNS
ownership, TLS certificate/renewal mechanism, credentials. Until provided,
local/container-only evidence stands and public claims stay forbidden.

## OCR-3 (merge/sync gate): integrated T13 branch and main specs

Status: RESOLVED FOR THE T13 INTEGRATION WORKTREE. Merge commit `19649b5`
integrates the current accepted T12 chain (including T07/T09/T10/T11 owner
handoffs) on top of the earlier main/T08 merges. Commit `070155f` synchronizes
all discovered T04–T12 delta capability paths into main specs; strict main-spec
validation passed 21/21. The change still remains Draft for unexecuted runtime,
fixture/history, digest, and external gates; ordinary pre-PR branch-local state
is not misclassified as a missing integration proof.

## OCR-4 (build hygiene): image tag→digest pairs unresolved

`deploy/.env.example` pins non-floating tags (maven/temurin/node/nginx-
unprivileged/mysql/redis) but no SHA256 digest has been resolved/recorded yet.
Blocks task 4.1 completion; refresh procedure documented in deploy/README.md.

## OCR-5 (T01/demo seed): fixture/token source undefined

Lane D needs a runtime-injected student token and representative
resource/slot fixture; demo orchestration depends on a T01-owned seed change
or approved ephemeral fixture (tasks 6.1). Absent either, affected runs are
BLOCKED by design rather than skipped silently.
**Concurrency extension**: the same fixture must also supply concrete
`resourceId/date/time` values for the JMeter same-slot rounds
(`deploy/jmeter/rounds.example.json` placeholders), a seeded scope where a
same-slot round can produce exactly 1 success + 99 conflicts, and a
RUNTIME-GENERATED 100-row distinct CSV (`token,resourceId,startTime,endTime`;
4 fields; no header; numeric distinct resourceIds). That CSV is a runtime
artifact — never committed — and its token column is consumed only by JMeter
at run time; run.ps1 validates structure without recording values.

**Demo extension (slice 5)**: the T13 ephemeral fixture assets are now DEFINED
(`deploy/demo/profile.example.json` + `deploy/demo/run.ps1`: run-id-scoped
users, T13-owned approval resource, deterministic PENDING booking, past
CONFIRMED seed left to the OWNER no-show scan). They remain UNEXECUTED and
UNATTESTED: `fixtureAttested` stays false until an owner review of the demo
lifecycle; until then StudentBrowser/E2E consume the fixture only in blocked
or planning form. The 184-assertion offline suite covers pre-I/O refusal,
secret lifecycle, random ownership-tag binding, incremental non-secret exact
parent/child-ID journaling, transactional partial compensation and full
exact-set teardown. Journaled tuples and child sets are revalidated and
range-locked in one SERIALIZABLE MySQL transaction; unjournaled child rows or
any cleanup mismatch cause rollback. A hard
interruption/API-response/journal-write
gap after mutation commit can still leave an unjournaled row, and no real
compensation run exists. Owner review must explicitly accept that residual
manual-recovery boundary before attestation.

## OCR-8 (approval owners): deterministic approval-browser fixture + approved command missing

The ApprovalBrowser lane (`deploy/e2e/run.ps1`) requires (1)
`approvalBrowserFixtureAttested: true`, (2) a repository-local owner root and
approved executable, and (3) a fresh owner-output manifest. Today no owner has
attested these inputs. T08 covers student flows only. A read-only audit found
`scripts/tests/t11/run.ps1`/`qa-harness.mjs` is a real raw-CDP candidate with
admin/student fixtures plus approve/reject UI, 401/403/404/409 evidence, PNG and
network output, but it is not adopted because:

- its credential is fixed in source rather than generated at runtime;
- approve/reject waits for current DOM changes but does not refresh the route and
  assert the matching API reload/persisted state;
- its finally closes Chrome but does not teardown the database fixture;
- its local redactor does not cover Authorization/cookies/full PII to T13's
  residual-scan standard.

T13 now has a 32-assertion offline intake contract. It passes a fresh empty
artifact root via positional RunId/output-root arguments, requires six exact
refresh case IDs (admin login, pending list, approve, reject, approved student
detail, rejected student detail), strict-boolean cleanup PASS, distinct
screenshot+network evidence, safe relative paths and fail-closed redaction
(including unscannable binary files under unlisted extensions). The owner
executable must be a repository-local, reparse-free `.exe`, or a `.ps1` run in
a separate `pwsh -NoProfile -File` child process. Even a structurally complete
owner output remains `EXECUTED_UNPROVEN`/exit 2 until owner/runtime/manual PNG
review closes OCR-8. Owner request: update and attest the T11 fixture/runner to
this contract; T13 will not modify `scripts/tests/**`, mock outcomes, or execute
the candidate before attestation.

## OCR-6 (owner/T08 history): vulnerable-baseline image/artifact unavailable

The `vulnerable-baseline` round requires a runnable stack WITHOUT the
`booking_slot` unique index (`uk_resource_slot`). No such image, compose file,
or migration variant exists in this repo, and T13 must not create one by
weakening frozen migrations. Owner request: provide (or attest how to rebuild)
the historical pre-index artifact in strict isolation. Blocked until then;
`rounds.example.json` keeps the round `enabled: false` with
`isolatedHistorical: true` and run.ps1 double-gates execution.

## OCR-7 (owner/T08/T09 history): unique-index-only artifact unverified

The `unique-index-only` round needs a historical image that HAS the unique
index but predates the Redisson wiring. Which Redis semantics apply must be
declared by that round's own image configuration; T13 does not guess the
current implementation. The corresponding historical compose/image artifact is
not present or attested — round stays blocked as configured.

## OCR-9 (T12): historical AvailabilityCacheKey JDK 17 compile failure

Status: RESOLVED; historical failing evidence retained as superseded.

Evidence from the authorized local run on 2026-08-28:

* Command: `cd booking-api; mvn -o -B verify`, with
  `JAVA_HOME=C:\Users\yuu\scoop\apps\temurin17-jdk\current`.
* Result: Maven compiler failed at
  `booking-api/src/main/java/com/yu030x/booking/cache/key/AvailabilityCacheKey.java:74`.
* The owner corrected the multi-catch to one variable after the alternatives.
* Final T12 real-environment `mvn verify` passed 387/387 with zero failures,
  errors, or skips on JDK 17; the earlier log remains a valid historical failure,
  not a current blocker.
* Exit code: `1`. Redacted raw log: `deploy/artifacts/local-backend-verify/mvn-verify-offline.log`.

No owner action remains for OCR-9. T13 must still preserve its own fresh backend
gate output before checking task 7.1.

## OCR-10 (T10): no-show processor fixture raced the live scheduler

Status: RESOLVED IN OWNER BRANCH. The first T13 backend gate failed 1/387 at
`NoShowMysqlIntegrationTest`; a targeted rerun passed 4/4, identifying a timing
race rather than a product failure. T10 changed processor-only fixtures to a
future non-scannable start so the processor selector owns time eligibility.
Owner commit `4ef792c` passed three consecutive 4/4 runs; the merged T13 backend
gate then passed 387/387.

## OCR-11 (T08): availability refresh evidence was sampled too early

Status: RESOLVED IN OWNER BRANCH. The first real StudentBrowser run reached
11/15 because case 08 inspected the refreshed availability journal before the
request completed, cascading into cases 09-11. T08 now waits up to 12 seconds
for a new availability payload (`6e51a98`). The merged final run passed 15/15;
this is a harness evidence fix, not a business/frontend source change.
