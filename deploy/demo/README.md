# T13 Demo Fixture (ephemeral runtime, NOT a migration seed)

> STATUS: **static only.** Nothing in this directory has ever been executed:
> no API registration, no SQL, no browser run. All demo evidence is DRAFT and
> `deploy/demo/evidence-index.template.md` is entirely NOT RUN placeholders.

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
* Teardown deletes children before parents (violation → approval → slot →
  booking → notification/blacklist → time rule → closure → resource → users),
  using EXACT username/purpose lists and the numeric resource id — no LIKE
  wildcards, no database/volume drops, no foreign rows. A pre/post total-count
  record plus leftover checks land in `teardown-evidence.txt`.
* Standalone Teardown requires an explicit fixture map via `-MapPath`; without
  it the mode is BLOCKED. The script never guesses the newest fixture scope.

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
