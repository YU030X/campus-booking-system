# Test harness entry points

Reusable acceptance/regression/environment harnesses live under
`scripts/tests/<scope>/`, each with a single documented Windows-first entry:
`run.ps1`. Generated screenshots, logs, credentials and run evidence are
ignored by a local `.gitignore` inside each scope directory.

## Scopes

### `t12/` — operation-log first slice (`add-supporting-capabilities` §1)

Entry: `pwsh scripts/tests/t12/run.ps1 [Check|List|Unit]`

- `Check` (default): static checks only — verifies the `log/**` source and test
  trees exist, runs `git diff --check`, and fails if any forbidden shared file
  (pom/application.yml/sql/deploy) shows drift.
- `List`: prints the unit-test inventory for this slice without executing it.
- `Unit`: runs the narrow Maven unit slice only
  (`mvn test -Dtest=com.yu030x.booking.log.**`) from `booking-api/`; no full
  `verify`, no MySQL/Redis required. Not executed during planning-only turns.
