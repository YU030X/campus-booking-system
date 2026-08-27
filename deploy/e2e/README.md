# T13 Integration / E2E Slice (planning artifacts)

> STATUS: **static only.** No maven, node, Chrome, or docker invocation has
> ever been made from this slice. tasks 1.4 and 2.1–2.5 stay unchecked until
> real runs produce redacted artifacts linked here.

## Files

| File | Purpose |
|---|---|
| `profile.example.json` | isolated local profile template: loopback frontend/backend, path placeholders, disposable DB/Redis namespace, credential **ENV NAMES only**, runner attestation, `publicAccessDenied: true` |
| `run.ps1` | Plan-mode default; `-Execute -Mode <ApiIntegration\|StudentBrowser\|ApprovalBrowser\|All>` |
| `redact-artifacts.mjs` | Node 18 built-ins offline redactor for T13 artifact text files; writes `redaction-manifest.json` (path/rule/count only) |
| `inventory.md` | requirement → concrete `file:class` mapping + T08 browser cases + honest gaps |

## Runner approval rationale

`scripts/tests/t08/run.ps1` + Chrome `--headless=new` (raw CDP, no puppeteer)
is the ONLY browser harness in this repository — reusing it is the
minimal-authority choice: its 15 student-flow cases already exist, its evidence
pipeline (network/console/api-driver JSONL + screenshots + REPORT) already
redacts Authorization headers and passwords, and its entrypoint is a single
documented `-Action Run` call. No new browser automation is introduced by T13;
the T08 harness is never copied or modified.

## Runtime flow (documented, NOT executed)

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
  local file path (executed directly via `& $exe @args`; shell strings and
  PATH guessing are refused; mocks forbidden); **this mode never reports
  pass** (executed ⇒ exit 2, `EXECUTED_UNPROVEN`, see OCR-8).
- **All** — Api → Student → Approval in order; any blocked or failing child
  makes the overall run non-zero.

## Redaction / privacy

* `redact-artifacts.mjs` processes ONLY the T13 run artifact directory
  (text extensions, ≤8MB per file), applying counted rules: Authorization
  Bearer, Cookie/Set-Cookie, sensitive JSON/kv fields, emails, CN mobile +
  bare 11-digit numbers, studentNo/realName fields. Oversize text files
  cannot be proven redacted and FAIL CLOSED (exit 2, recorded as
  `OVERSIZE_UNREDACTED`). After writing it re-scans for live sensitive
  patterns (bearer, cookie, JSON/kv values, emails, phones, PII fields) and
  exits 2 on any residual hit.
* `redaction-manifest.json` contains path/rule/count only — never matched
  values.
* T08 originals are never modified; run.ps1 copies first, redacts the copy.
* Screenshots cannot be auto-redacted: they carry a manual visual PII review
  marker instead.

## Honest gaps / blockers

* ApprovalBrowser: deterministic fixture + approved command missing (OCR-8).
* Per-state browser refresh matrix (approve/check-in/no-show) not automated —
  API-level equivalents covered (see `inventory.md` gaps).
* Fixture attestation for StudentBrowser has not been granted by the T01
  fixture owner yet (`fixtureAttested: false` in the template).
* The integration env-gates (RESOURCE_MYSQL_URL etc.) have never been supplied
  in this environment; first Execute will BLOCK until the disposable namespace
  is provisioned.
