# T13 Integration / E2E Slice

> STATUS: **partially executed.** ApiIntegration passed 195/195 across the fixed
> 37-class inventory. StudentBrowser passed 15/15 in Chrome 152; its text
> residual scan is zero and all 52 screenshots were manually reviewed.
> ApprovalBrowser remains blocked by OCR-8; its owner-output contract passes 32
> offline assertions but no approval browser was executed. Docker-dependent E2E
> is not implied.

## Files

| File | Purpose |
|---|---|
| `profile.example.json` | isolated local profile template: loopback frontend/backend, path placeholders, disposable DB/Redis namespace, credential **ENV NAMES only**, runner attestation, `publicAccessDenied: true` |
| `run.ps1` | Plan-mode default; `-Execute -Mode <ApiIntegration\|StudentBrowser\|ApprovalBrowser\|All>` |
| `approval-contract-tests.ps1` | offline synthetic owner-output contract: attestation/command/root/argv/freshness, six refresh cases, cleanup, redaction and permanent `EXECUTED_UNPROVEN` |
| `redact-artifacts.mjs` | Node 18 built-ins offline redactor for T13 artifact text files; writes `redaction-manifest.json` (path/rule/count only) |
| `inventory.md` | requirement → concrete `file:class` mapping + T08 browser cases + honest gaps |

## Runner approval rationale

`scripts/tests/t08/run.ps1` + Chrome `--headless=new` (raw CDP, no puppeteer)
is the selected owner-approved student browser harness. Reusing it is the
minimal-authority choice: its 15 student-flow cases already exist, its evidence
pipeline (network/console/api-driver JSONL + screenshots + REPORT) already
redacts Authorization headers and passwords, and its entrypoint is a single
documented `-Action Run` call. No new browser automation is introduced by T13;
the T08 harness is never copied or modified. A separate T11 raw-CDP approval
candidate now exists, but it is not silently adopted: it lacks generated
credentials, finally teardown, complete redaction and the required post-state
refresh matrix, and remains an OCR-8 owner action.

## Runtime flow

```powershell
pwsh deploy/e2e/run.ps1                                   # plan mode
$env:T13_STUDENT_TOKEN='...' # (only where a browser case needs it)
pwsh deploy/e2e/run.ps1 -Execute -Mode ApiIntegration     # mvn narrow class set
pwsh deploy/e2e/run.ps1 -Execute -Mode StudentBrowser     # t08 harness -Action Run
pwsh deploy/e2e/run.ps1 -Execute -Mode ApprovalBrowser    # blocked unless owner attestations exist
pwsh deploy/e2e/run.ps1 -Execute -Mode All                # sequential; any failure => non-zero
```

Mode semantics:

- **ApiIntegration** — the explicit class list is hardcoded in run.ps1 and
  mirrored in `inventory.md`; every class file is verified before mvn runs and
  a missing class BLOCKS (exit 3) rather than silently narrowing coverage.
  Env contract: `DB_URL` and `REDIS_HOST` are hard-required in the host env;
  `RESOURCE_MYSQL_URL`/`USER_CREDIT_MYSQL_URL` are derived from `DB_URL` when
  absent; `REDIS_PORT` defaults to 6379. Credential values are injected from
  the profile-declared ENV NAMES (`dbUsernameEnv/dbPasswordEnv/jwtSecretEnv/
  redisPasswordEnv` → `DB_USERNAME/DB_PASSWORD/JWT_SECRET/REDIS_PASSWORD`,
  plus `RESOURCE_MYSQL_USERNAME/PASSWORD`); missing DB user/pass/JWT names
  BLOCK the run. Every touched env var is precisely restored afterwards.
  Logs are redacted by `redact-artifacts.mjs` after capture.
- **StudentBrowser** — requires `profile.fixtureAttested: true`; sets
  `T08_QA_FRONTEND`/`T08_QA_BACKEND` to the profile loopback URLs and calls the
  T08 harness `-Action Run`. Only run directories CREATED BY THIS EXECUTION
  count as evidence (pre-existing `run-*` dirs are snapshotted and excluded; a
  run that produces no new directory FAILS instead of presenting stale
  evidence). Publishable TEXT artifacts are copied and redacted; PNGs are
  copied to `screenshots-unreviewed/` with a
  `REQUIRES-MANUAL-VISUAL-PII-REVIEW.txt` marker + index — never claimed
  redacted or passed automatically.
- **ApprovalBrowser** — if `approvalBrowserFixtureAttested` is false ⇒ BLOCKED
  (exit 3). Even when attested, execution requires an owner-approved
  `approvalBrowserCommand` as a JSON ARRAY whose first element is an EXISTING
  `.exe` or `.ps1` file (`.bat`/`.cmd`/interpreter scripts are refused) below a
  repository-local owner root whose every component is a real directory —
  junctions/symlinks (reparse points) anywhere on that chain are refused.
  `.ps1` owner runners execute in a separate `pwsh -NoProfile -File` child
  process, never inside the T13 session. Positional argv[1] and argv[2] must
  be `{T13_RUN_ID}` and `{T13_ARTIFACT_ROOT}`; shell strings and PATH guessing
  are refused. T13 supplies a fresh, empty, non-reparse output directory,
  redacts all text, rejects stale/unsafe/missing/duplicated evidence,
  requires strict-boolean cleanup PASS (`"false"` as a string never passes)
  and six exact refresh cases with distinct screenshot+network files (the
  manifest itself cannot serve as network evidence), and marks every
  referenced PNG — including extra cases — for manual review. **This mode
  never reports pass** (executed ⇒ exit 2, `EXECUTED_UNPROVEN`, even when the
  structural contract is complete).
- **All** — Api → Student → Approval in order; any blocked or failing child
  makes the overall run non-zero.

## Redaction / privacy

* `redact-artifacts.mjs` processes ONLY the T13 run artifact directory
  (≤64MB per file), applying counted rules: Authorization Bearer/Basic,
  api-key/x-api-key headers, Cookie/Set-Cookie, sensitive JSON/kv fields,
  raw JWTs, emails, CN mobile + bare 11-digit numbers, studentNo/realName
  fields. Text extensions are redacted; known binary extensions (screenshots)
  are recorded as `SKIPPED_BINARY`; any OTHER extension is sniffed — text-like
  content is redacted, binary-like content is recorded as
  `UNSCANNED_BINARY_UNLISTED_EXT` and FAILS CLOSED. Oversize text files
  cannot be proven redacted and FAIL CLOSED (exit 2, recorded as
  `OVERSIZE_UNREDACTED`). After writing it re-scans for live sensitive
  patterns (bearer/basic, api-key, cookie, JSON/kv values, JWTs, emails,
  phones, PII fields) and exits 2 on any residual hit.
* `redaction-manifest.json` contains path/rule/count only — never matched
  values.
* T08 originals are never modified; run.ps1 copies first, redacts the copy.
* Screenshots cannot be auto-redacted: they carry a manual visual PII review
  marker instead.

## Honest gaps / blockers

* ApprovalBrowser: deterministic fixture + approved owner root/command missing
  (OCR-8). The 32-assertion offline suite proves only the T13 intake contract.
* Trust boundaries: `-ArtifactRoot` is operator-consented (evidence may be
  collected outside the repository if the operator points there), and the
  owner executable inherits the host environment (host-required `DB_URL` /
  `REDIS_HOST` included); credential values for test lanes are injected only
  as profile-declared variable names and restored/removed in `finally`.
* The audited T11 candidate changes approve/reject DOM state but does not reload
  and prove the route/API state after each transition; its fixed credential,
  incomplete redaction and missing finally teardown also require owner changes.
  API-level equivalents remain covered (see `inventory.md` gaps).
* The committed template remains safely unattested; the actual run used an
  ignored, loopback-only profile plus the existing T08 scoped seed/teardown.
* Evidence: `deploy/artifacts/e2e-ApiIntegration-t13-api-integration-pass/` and
  `deploy/artifacts/e2e-StudentBrowser-t13-student-browser-final/` (ignored).
  The latter contains a PASS 15/15 report, zero-residual redaction manifest,
  and 52/52 manually reviewed PNGs containing generated QA data only.
