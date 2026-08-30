# Test harness entry points

Reusable acceptance/regression/environment harnesses live under
`scripts/tests/<scope>/`, each with a single documented Windows-first entry:
`run.ps1`. Generated screenshots, logs, credentials and run evidence are
ignored by a local `.gitignore` inside each scope directory.

## Scopes

### `t11/` — administrator operations frontend (`add-web-admin-operations`)

Entry: `powershell -ExecutionPolicy Bypass -File scripts/tests/t11/run.ps1 -Mode All`.
Modes: `Check` boundary/diff checks, `List` case inventory, `Unit` Node tests,
and `All` (default). Browser run evidence lands in the scope-local ignored
`artifacts/` directory.

### `t12/` — operation-log + availability-cache + notifications + statistics (`add-supporting-capabilities` §1–§4)

Entry: `pwsh scripts/tests/t12/run.ps1 [Check|List|OperationLog|Cache|RealCache|Notifications|Statistics|Frontend|Flags|CutMatrix|Unit]`
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
- `RealCache`: real MySQL 8 + Redis 7 cache/owner integration selection.
- `Notifications`: `mvn test -Dtest=com.yu030x.booking.notification.**`
  (includes MySQL-backed integration classes; requires database access when run).
- `Statistics`: `mvn test -Dtest=com.yu030x.booking.statistics.**`
  (includes MySQL-backed integration/EXPLAIN-oriented classes; requires database access when run).
- `Frontend`: notification/statistics Node contract tests followed by the
  production `npm run build`.
- `Flags`: validates all four independent opt-in/default-false contracts.
- `CutMatrix`: cuts statistics, notifications, then cache and reruns the full
  `booking/**` T07 selection at every stage.
- `Unit`: union selection of all four slice patterns in one surefire run:
  `-Dtest=com.yu030x.booking.log.**,com.yu030x.booking.cache.**,com.yu030x.booking.notification.**,com.yu030x.booking.statistics.**`.

Artifacts: `t12/.gitignore` keeps run evidence (logs, output files) untracked;
it also stays in place for future real executions.
