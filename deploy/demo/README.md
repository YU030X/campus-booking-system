# T13 Demo Fixture (ephemeral runtime, NOT a migration seed)

> STATUS: **implementation contract-tested; real Demo NOT RUN.** No API
> registration, SQL, browser or teardown runtime has executed. All demo evidence
> remains DRAFT and `evidence-index.template.md` remains NOT RUN placeholders.

## Offline contract check

```powershell
pwsh deploy/demo/contract-tests.ps1
```

The suite exercises only Plan and pre-I/O refusal paths, then statically checks
the password/temporary-secret/journal/transactional-compensation/teardown/
evidence contracts. The current suite passes 119 assertions. A PASS is not
fixture attestation or Demo acceptance and does not complete tasks 6.1-6.4. The
suite is also part of `deploy/verify/run.ps1 -Mode Check -Gate static`.

## What this is (and is not)

* `run.ps1` creates an **ephemeral runtime fixture** inside the local compose
  MySQL: three run-id-scoped users (`t13demo_<runid>_admin|student|intruder`),
  one T13-owned category, approval resource + time rule (created through the ADMIN API),
  one deterministic PENDING booking (created through the student API), and one
  PAST CONFIRMED booking inserted by direct SQL **strictly labeled
  `EPHEMERAL-SETUP-NOT-ACCEPTANCE-EVIDENCE`** — the OWNER no-show scan task
  (≤1/min) is expected to turn it into violation + credit deduction itself;
  the script only waits/records the outcome.
* The existing `scripts/tests/t08/seed.sql` is checked only as the OWNER fixture
  reference. It is **never executed** because it contains destructive DELETE/
  INSERT statements for the T08 scope. The T08 file is never modified and
  remains owned by T08; this demo creates its own category/resource instead.
* This directory writes NO migration, NO seed file, and NO business data that
  outlives the run. Teardown removes exactly the fixture-owned rows.

## Setup / teardown safety

* Scope: usernames derived from the RunId (`t13demo_<runid-with-underscores>*`,
  matching the API username charset), purposes prefixed `T13DEMO:<runid>:`,
  and the demo resource id recorded in `fixture-map.json`.
* Before its first mutation, Setup queries the exact three usernames, category
  name, resource name and two purpose strings and requires the whole RunId scope
  to be empty. A retry/collision is refused for recovery review, never adopted.
  After that all-zero preflight and still before mutation, it writes a non-secret
  `recovery-scope.json` containing only these deterministic names/purposes and
  counts; this is explicitly not an executable teardown map.
* Teardown deletes children before parents (violation → approval → slot →
  booking → notification/blacklist → time rule → closure → resource → users),
  using EXACT username/purpose lists and the numeric resource id — no LIKE
  wildcards, no database/volume drops, no foreign rows. A pre/post total-count
  record plus leftover checks land in `teardown-evidence.txt`.
* Before the first delete, Teardown requires distinct mapped booking/user ids and
  verifies the current database still matches every destructive owner tuple:
  user id+username, resource id+name+category, and booking id+purpose+student+
  resource. A numeric but foreign/tampered map is refused with zero deletes.
* Standalone Teardown requires an explicit fixture map via `-MapPath`; without
  it the mode is BLOCKED. The script never guesses the newest fixture scope.
* Before the first mutation, Setup creates a non-secret
  `partial-fixture-journal.json`; after each successful entity creation it records
  the exact numeric id and deterministic owner tuple before the next setup phase.
  If Setup then fails without a complete map, `finally` revalidates every recorded
  tuple and rejects unjournaled notification/blacklist or resource-child rows.
  Its authoritative recheck and children-first deletes run in one SERIALIZABLE
  MySQL transaction with `FOR UPDATE` locks on parent rows and the complete
  notification/blacklist/time-rule/closure ranges; any ownership/cleanup
  mismatch selects `ROLLBACK`, never a half-committed compensation.

## Generated passwords

* One cryptographically random password per user (32 random bytes), generated
  at Execute time. Passwords and login tokens are stored ONLY in a randomly
  named system-temp JSON for the same run; `finally` deletes it on every path
  and verifies deletion — a failed deletion fails the run with a rotation
  instruction. Nothing secret is written into `fixture-map.json` (ids,
  usernames, purposes, timestamps only) or any artifact.
* Standalone `StudentFlow` needs no fixture password: the T08 harness
  registers its own browser users.

## Evidence status

* `StudentFlow` delegates to `deploy/e2e/run.ps1 -Mode StudentBrowser` only when
  the supplied profile has owner-reviewed `fixtureAttested: true`; the temporary
  child profile inherits that attestation and cannot self-attest. Resulting
  evidence is accepted only from a directory created by that exact invocation,
  then retained as redacted text + an unreviewed-screenshot
  directory, mapped into `evidence-index.md`.
* The APPROVAL browser path is **blocked** (OCR-8): no deterministic approval
  fixture/command exists, ApprovalBrowser is never invoked here, and nothing
  in this slice may claim it passed.
* RPO/RTO-style demo claims, screenshots publication, and any public URL are
  out of scope and remain NOT RUN.

## Honest gaps

* Never executed end-to-end; the API shapes (register/login/resource/booking
  payloads) were transcribed from the current DTOs and controllers but not
  exercised.
* The owner no-show scan producing the violation depends on a running backend
  with scheduling enabled; if it does not fire within the wait window the run
  records `pending-owner-scan` and does NOT fabricate a violation.
* Fixture attestation (OCR-5) is a template field only until an owner review.
* If Setup fails after journaled rows exist but before `fixture-map.json`, the
  current `All`/`Setup` finally path can transactionally compensate only those
  recorded and revalidated tuples. This design has passed offline source
  contracts, and a no-business-write MySQL 8.0.40 probe confirmed the conditional
  COMMIT/ROLLBACK marker mechanism; no real compensation run exists.
* A hard process interruption, API response loss, or journal write failure in the
  gap after a mutation commits but before its tuple is persisted can still leave
  an unjournaled row. `recovery-scope.json` and exact namespace preflight preserve
  an honest manual recovery boundary; owner review must accept this residual gap
  before attestation or a real Demo run.
