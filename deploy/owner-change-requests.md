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

## OCR-4 (build hygiene): runtime base tag→digest refresh

Status: PARTIALLY RESOLVED IN T13 — the runtime-base portion is complete; Java
application dependencies remain a release blocker and are tracked by OCR-12.

The 2026-09-03 refresh resolved and verified the linux/amd64 runtime references:

* `eclipse-temurin:17.0.20_8-jre-jammy@sha256:e17d77fb030dd4b642dc078d048a5fb9efcb3676ee20305d905949105a6ccd5a`
* `nginxinc/nginx-unprivileged:1.30-alpine3.24@sha256:9b87ad3dd9f431c733f19dfb278c7eb3dba9dca381942c79818bb42f1a566a83`

The immutable defaults are present in `deploy/.env.example`, both Dockerfiles,
and the Compose build args. Pull/build metadata and the resulting API/edge image
IDs are recorded in `deploy/artifacts/t13-image-pulls-20260903/` and
`deploy/artifacts/t13-image-build-20260903-runtime-refresh/`; both runtime-base
HIGH/CRITICAL pre-scans returned 0/0. The full API scan still reports 7 CRITICAL
and 30 HIGH Java application-dependency findings, so `Validate` returns
`VALIDATED_SCAN_BLOCKS_RELEASE` (exit 2) and task 7.4 remains unchecked.

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
review closes OCR-8. RESOLVED (2026-09-04, user decision 4): the T11 owner branch delivered
`approval-contract-runner.ps1/.mjs` (generated credentials, scoped cleanup-first
fixture, six refresh cases with API-reload persistence, atomic arm/confirm, 582
lines, committed `349e070`), was merged into T13 (`bff8464`), attested by the
user, and executed through the hardened lane: contractComplete=true,
EXECUTED_UNPROVEN by design, 6/6 PNGs manually reviewed, exact-scope cleanup
verified. OCR-8 is closed; the lane's permanent non-pass status is a by-design
guardrail, not a remaining gap.

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

## OCR-12 (T01/shared dependency owner): Java application dependency findings

Status: OPEN — blocks T13 task 7.4 and release approval. T13 must not edit
`booking-api/pom.xml`, shared dependency management, or business code.

Evidence: the real offline Trivy run `t13-real-scan-20260903-runtime-refresh`
validated the rebuilt API image and retained exactly 37 HIGH/CRITICAL finding
rows (30 HIGH, 7 CRITICAL) in
`deploy/artifacts/t13-real-scan-20260903-runtime-refresh/api-high-critical.tsv`
(header plus rows 2–38). Every row is a Java target with
`Source=application-dependency`; the edge image is 0/0. Runtime-base
HIGH/CRITICAL pre-scans are 0/0, so these findings are not a base-image issue.

Owner request:

Independent reproduction (2026-09-04, this session): a fresh `--no-cache`
rebuild after aligning the local ignored `deploy/.env` pins with the remediated
defaults reproduced the exact 37 HIGH/CRITICAL rows (edge 0/0) — new evidence
`deploy/artifacts/t13-real-scan-20260904-rebuild/` (validator
`VALIDATED_SCAN_BLOCKS_RELEASE`, DB age 37.9h). Operational finding recorded:
the ignored local `deploy/.env` was still pinning the OLD bases and silently
overrode the remediated compose defaults; it now carries the same digest-pinned
values. No pom/code was edited by T13.

1. Select compatible patch/minor versions for the Java 17/Spring Boot 3.5
   contract (including the managed Jackson, Micrometer, Netty, Tomcat, Spring
   Boot/Data/Security/Core/Web artifacts) and record the compatibility rationale;
   do not take an unreviewed major-line jump merely to silence the report.
2. Attach a complete `mvn dependency:tree` result that maps each finding to its
   direct or transitive owner and explains any dependency-management override.
3. Apply the dependency refresh in the owning T01/shared-dependency change,
   then run `cd booking-api && mvn verify` and preserve the real exit code and
   test summary.
4. Rebuild API and edge from the refreshed checkout with the immutable runtime
   references (`docker compose ... build --pull=false api edge`), recording
   linux/amd64 image IDs and runtime RepoDigests.
5. Run a fresh offline Trivy scan of both rebuilt images and execute
   `deploy/scan/run.ps1 -Action Validate` against the new manifest. Provide the
   raw reports, execution log, hashes, and normalized result; release requires
   scanner exits 0 and UNKNOWN/HIGH/CRITICAL counts of zero for both images.

The current scan manifest records global advisory-DB metadata/checksum, but its
validator schema does not carry per-finding Java DB provenance. Preserve that
limitation as residual risk and do not broaden the schema unless an existing
specification explicitly requires it.
