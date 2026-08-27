# Test harness entry points

Reusable acceptance/regression/environment harnesses live under
`scripts/tests/<scope>/`, each with a single documented Windows-first entry:
`run.ps1`. Generated screenshots, logs, credentials and run evidence are
ignored by a local `.gitignore` inside each scope directory.

## Scopes

### `t12/` — operation-log + availability-cache + notifications + statistics (`add-supporting-capabilities` §1–§4 backend slices)

Entry: `pwsh scripts/tests/t12/run.ps1 [Check|List|OperationLog|Cache|Notifications|Statistics|Unit]`
(default `Check`). Maven modes are narrow Surefire selections from
`booking-api/`; no `verify`, no aggregation into the full build.

- `Check` (default): static only — verifies all four main/test trees
  (`com/yu030x/booking/{log,cache,notification,statistics}`) exist and hold
  sources, runs `git diff --check`, and aborts on local drift inside strongly
  forbidden shared paths (pom.xml, `src/main/resources`, `sql/`, `deploy/`,
  `docs/`, `booking-web/`). Other local drift lines are printed as informational.
- `List`: prints the complete test inventory of the four slices without
  executing anything.
- `OperationLog`: `mvn test -Dtest=com.yu030x.booking.log.**`
- `Cache`: `mvn test -Dtest=com.yu030x.booking.cache.**`
- `Notifications`: `mvn test -Dtest=com.yu030x.booking.notification.**`
  (includes MySQL-backed integration classes; requires database access when run).
- `Statistics`: `mvn test -Dtest=com.yu030x.booking.statistics.**`
  (includes MySQL-backed integration/EXPLAIN-oriented classes; requires database access when run).
- `Unit`: union selection of all four slice patterns in one surefire run:
  `-Dtest=com.yu030x.booking.log.**,com.yu030x.booking.cache.**,com.yu030x.booking.notification.**,com.yu030x.booking.statistics.**`.

**Execution status:** none of these Unit/slice invocations have been executed
yet — they were authored for reuse; acceptance evidence must come from a real
run recording exact commands and results.

Artifacts: `t12/.gitignore` keeps run evidence (logs, output files) untracked;
it also stays in place for future real executions.
