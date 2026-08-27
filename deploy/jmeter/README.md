# T13 JMeter Concurrency Slice (planning artifacts)

> STATUS: **static only**. The plan, config template and scripts have never been
> executed: no jmeter, docker, or parser invocation has happened from this
> branch. tasks 3.1–3.4 stay unchecked until real runs produce raw JTL and
> reports linked here.

## Files

| File | Purpose |
|---|---|
| `booking-concurrency.jmx` | JMeter 5.6.3 plan; same-slot ThreadGroup with EXACTLY 100 threads (ramp 1, loop 1); distinct resource/date group gated separately; POST `/api/v1/bookings` with contract JSON fields; no Response Assertions (classification is offline) |
| `rounds.example.json` | three rounds `vulnerable-baseline` / `unique-index-only` / `unique-index-redisson`; placeholders only |
| `run.ps1` | plan-mode default; `-Execute -RoundId <id>` runs ONE round against a loopback stack; same-slot token via temp secret properties (never argv), distinct via runtime CSV path; captures JTL + pre/post row evidence + env metadata |
| `summarize.ps1` | pure offline XML JTL classification into mutually exclusive classes (incl. `data_error`); protected-round 1/99/0 + row-delta assertions; redacted digest-only reports |

## Run flow (documented, NOT executed)

```powershell
pwsh deploy/jmeter/run.ps1                                   # plan mode: lists rounds
$env:T13_STUDENT_TOKEN = '<runtime injected>'
pwsh deploy/jmeter/run.ps1 -Execute -RoundId unique-index-redisson
pwsh deploy/jmeter/summarize.ps1 -RunDir deploy/artifacts/jmeter-unique-index-redisson-<runid>
# historical baseline additionally requires config isolatedHistorical=true AND:
pwsh deploy/jmeter/run.ps1 -Execute -RoundId vulnerable-baseline -AllowHistoricalBaseline
```

## Privacy / redaction

* SAME-SLOT token path: `T13_STUDENT_TOKEN` (env only) is written to a RANDOMLY
  named SECRET properties file under the system temp directory and loaded via
  a SECOND `-q` argument. The token value never appears in jmeter argv, in the
  committed `jmeter-run.properties`, or in artifacts. File creation, jmeter
  invocation and post-run scrub all sit inside one `try` whose `finally`
  deletes the temp file unconditionally on every path (including jmeter
  aborts); deletion is verified — a failed deletion fails the run and
  instructs token rotation.
* DISTINCT token path: run.ps1 does NOT use `T13_STUDENT_TOKEN`. The round
  config points at a runtime-generated CSV (columns
  `token,resourceId,startTime,endTime`; 4 fields; **no header line**; exactly
  100 rows). run.ps1 validates structure only (non-empty token per row,
  numeric distinct resourceIds, formatted times) and passes ONLY the CSV path
  property. Per-row token values are never logged or recorded.
  **CSV files are runtime artifacts and must never be committed.**
* `jmeter-run.properties` keeps `request_headers` and `samplerData` OUT of the
  XML JTL; response bodies are saved because offline classification needs them.
* After each run, `run.ps1` scans the JTL and log for the same-slot token
  bytes and scrubs them, warning if anything leaked.
* **Reports carry no raw response bodies or data payloads.** Per class,
  `report.json/.md` include at most the first sample's
  `{httpStatus, code, message, category}` with `message` passed through a
  sensitive key/value masking regex. The raw JTL stays a local git-ignored
  artifact; publishing it requires a separate redaction plus a dedicated
  secret/PII scan (out of T13's automated scope).
* No real secret, token or password belongs in `rounds.example.json` or any
  committed artifact — placeholders only.

## Three-round comparability

* Same plan, same 100-thread/1s-ramp/1-loop shape; rounds vary ONLY the
  concurrency-control version (declared per round in `historyMirror`).
* `vulnerable-baseline` runs a historical image WITHOUT the `booking_slot`
  unique index; it is disabled by default, requires
  `isolatedHistorical: true` AND `-AllowHistoricalBaseline`, and its duplicate
  successes are labeled **historical vulnerability evidence** — never measured
  against the 1/99 contract.
* `unique-index-only` declares its Redis semantics from its own historical
  image (current implementation is not assumed). Its Redis SERVICE is expected
  healthy (`redisExpectedHealthy: true`) even though the historical app may
  not consume the lock; `summarize.ps1` treats a round as protected only with
  **strict AND**: `redisExpectedHealthy == true` AND observed `redisObserved ==
  'healthy'` AND `validSeed` — one without the other is not protected.
* `unique-index-redisson` with healthy Redis + valid seed is the protected
  round where `summarize.ps1` asserts exactly 1 success + 99 business
  conflicts + 0 system_busy + 0 data_error + 0 server/connection/other, and
  row deltas: booking delta == success count, booking_slot delta ==
  success count × `slotsPerBooking` (run-recorded from the same-slot span,
  which must be a positive 30-minute-aligned integer or the run refuses).
* SYSTEM_BUSY (HTTP 409 + code 43000 + exact SYSTEM_BUSY message/category) is
  a separate `system_busy` error class — never counted as a business conflict.
  Business conflict requires 409 + code 43000 + the exact SLOT_CONFLICT
  message (`该时段已被占用，请刷新后重试`) or a SLOT_CONFLICT /
  BOOKING_SLOT_CONFLICT category; 41000/42000 codes alone never qualify.
  A `data_error` class flags envelope-bearing responses that are unparseable,
  lack canonical code/message, or 201s whose code/data do not conform.
* Distinct resource/date scope runs in its own execution (run.ps1 refuses to
  mix scenario flags) and reports lock-granularity distribution only.
* BaseUrl is deeply validated before execution: http/https scheme, loopback
  host, and empty path/query/userinfo/fragment — anything that the JMX
  domain/port split would silently drop is refused instead.

## Blockers / honest gaps

* **No fixture values yet**: every `resourceId/date/time` is a `<placeholder>`
  pending the T01 fixture contract (OCR-5) — the plan cannot run until filled.
* **No historical baseline artifact**: an image/stack WITHOUT the unique index
  does not exist in this repo; building/acquiring it is a separate owner
  request (OCR-6).
* **unique-index-only round artifact** (historical image with index but without
  Redisson) is equally missing (OCR-7).
* JMeter is not installed/verified in this environment; `jmeter --version` has
  never been run here.
* XML JTL parsing performance for large runs is untested; if memory becomes an
  issue the summarize parser will need streaming — noted, not solved.
