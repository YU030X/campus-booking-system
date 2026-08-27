# T13 Owner Change Requests & External Blockers

Standing rule: T13 never edits business/frontend/common source, Maven/npm
manifests, or `sql/` migrations. Every defect below is a REQUEST to the owning
task; T13 only records evidence and blocks its own gates accordingly.

## OCR-1 (T12): availability read path does not consume the cache port

Status: OPEN - blocks lane D full pass, tasks 5.4, parts of E2E Redis-failure
coverage, and any "cache is active" claim in deployment evidence.

Evidence:

* The cache port exists and is conditional:
  * `booking-api/src/main/java/com/yu030x/booking/cache/config/AvailabilityCacheConfiguration.java:19` —
    `@ConditionalOnProperty(name = "booking.cache.enabled", havingValue = "true", matchIfMissing = false)`
  * same file, line 23: `availabilityCachePort(ObjectProvider<RedissonClient>)`
    bean defined; line 29 shows a second bean injecting `AvailabilityCachePort`.
* But the read path bypasses it entirely:
  * `booking-api/src/main/java/com/yu030x/booking/availability/AvailabilityService.java:29-33` —
    constructor injects `ResourceMapper`, `ResourceTimeRuleMapper`,
    `ResourceClosureMapper`, `BookingSlotMapper`, `Clock`; there is no
    `AvailabilityCachePort` field/consumer anywhere in the class.
* Therefore with Redis stopped, an availability GET exercises plain mappers;
  observing success proves nothing about MySQL fallback THROUGH the cache layer,
  and `BOOKING_CACHE_ENABLED=true` in compose merely instantiates beans nobody calls.

Requested owner action (T12): route availability slot reads through
`AvailabilityCachePort` with documented MySQL fallback semantics, preserving
frozen API responses. After merge + spec sync, T13 re-runs lane D WITH
`-T12FallbackWired`.

## OCR-2 (external/user): public acceptance inputs missing

Blocks tasks 8.1–8.3 by design. Missing: authorized target host/domain, DNS
ownership, TLS certificate/renewal mechanism, credentials. Until provided,
local/container-only evidence stands and public claims stay forbidden.

## OCR-3 (merge/sync gate): branch-local integration exists, main + spec sync do not

Current status on the T13 branch: merge commits integrate main and the T09/T08/
T11/T12 lanes — `1753903` (main), `652513b`, `240a7b2`, `974a213`, `13c51e0` —
so the worktree is NOT "unmerged planning artifacts" anymore. The gate is
nonetheless only PARTIAL because:

1. these commits are not merged into `main` (branch-local integration),
2. delta specs are not synced into main specs (openspec sync step pending),
3. full P0 per-owner merge/rebase evidence is not collected/indexed.

Owner(s): each lane owner for their merge/rebase proof; T13 owns collecting and
indexing the evidence but cannot self-certify `main` state or spec sync.
Consequence: execution-oriented local gates may only run as planning evidence;
the change stays Draft until 1–3 are closed.

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
