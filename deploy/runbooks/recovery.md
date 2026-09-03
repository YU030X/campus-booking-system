# T13 Recovery & Deployment Runbook

> STATUS: **partially executed** — empty migration, backup/restore and restart
> persistence and Redis outage have passing local evidence.

Scope: local/container evidence lanes only. Public/external recovery actions
require explicit user authorization (tasks 8.1–8.2) before any run.

## Lanes overview

| Lane | Script | Proves | Status |
|---|---|---|---|
| Empty migration | `scripts/empty-migration-check.ps1` | V001–V005 on two throwaway MySQL8 containers produce identical 12-table InnoDB/utf8mb4 seed-free schemas with all declared keys | PASS: `t13-empty-migration-20260901-final` |
| Backup / restore | `scripts/backup-restore-check.ps1` | consistent dump restores into an isolated DB with matching definitions/checksums/aggregates and RTO comparison | PASS: `t13-backup-restore-20260901-nonzero` |
| Restart persistence | `scripts/restart-persistence-check.ps1` | schema/count identity and health across volume-preserving restart | PASS: `t13-restart-persistence-20260901-audited` |
| Redis failure | `scripts/redis-failure-check.ps1` | T07 fail-closed plus T12 live MySQL fallback, zero mutation, latency, and Redis recovery | PASS: `t13-redis-outage-20260901-final` |

Common rules: loopback/private endpoints only; run-id scoped containers,
volumes and artifacts; evidence written to `deploy/artifacts/<run-id>/`
(git-ignored); credentials arrive via environment, never on command lines;
every script cleans up throwaway objects in `finally`.

## 1. Empty-database migration lane

Applies the frozen migrations to two fresh databases via read-only bind mounts;
asserts exactly the twelve contract tables, InnoDB engine, utf8mb4 collation,
zero rows (seed-free), every DDL-declared unique key present with exact column
sets, then compares normalized information_schema fingerprints by SHA256.

```powershell
pwsh deploy/scripts/empty-migration-check.ps1            # default run
pwsh deploy/scripts/empty-migration-check.ps1 -KeepOnFailure   # retain volumes on failure
```

Stop conditions: any mismatch fails the lane (exit 2); missing migration files
fail closed before docker is invoked (exit 1).

## 2. Backup / restore lane

Requires the local compose stack running. No host-side password is read or
required: mysqldump/mysql authenticate inside the container via
`MYSQL_PWD="${MYSQL_ROOT_PASSWORD}"`.

```powershell
pwsh deploy/scripts/backup-restore-check.ps1 `
  -RpoAssumption '24 hours' -RtoAssumption '4 hours' -RtoSeconds 14400
```

Flow: consistent dump inside container → `docker cp` to artifacts (sha256
recorded) → create random isolated DB `t13_restore_*` → replay dump → compare
table sets, per-table checksums, unique-index definitions, booking/booking_slot
row counts and id aggregates → record measured restore seconds and assert it is
within the numeric RTO threshold. RPO/RTO remain operator-owned inputs.
Guarantees: source schema and named volumes are never dropped; cleanup removes
only the restore DB and remote temp dump.

## 3. Restart / recreate lane (volume-preserving)

Plan-only by default — review the printed plan, then execute deliberately:

```powershell
pwsh deploy/scripts/restart-persistence-check.ps1                 # plan only
pwsh deploy/scripts/restart-persistence-check.ps1 -Execute        # restart services
pwsh deploy/scripts/restart-persistence-check.ps1 -Execute -Recreate   # stop + up --force-recreate
```

Forbidden forever in this lane: `compose down`, `down -v`, `container rm`, or
any `-v` flag. The script's plan artifact records that rule next to the exact
commands it will use. Health gate polls mysql readiness before comparing pre/post
fingerprints and row counts; compose config + log tails land in artifacts.

## 4. Redis outage matrix

Contract sources: booking lock consumer fails closed
(`BookingLockCoordinator.java` lines 29–65 throwing `SYSTEM_BUSY` /
code 43000 on RedisException) while the availability cache consumer may fall
back to MySQL (`RedisProperties.java:14`,
`AvailabilityCacheConfiguration.java:23` object-provider wiring).

| Consumer | During outage | Expected | Proof point |
|---|---|---|---|
| T07 booking lock | `compose stop redis`, POST `/api/v1/bookings` | HTTP 409 + code 43000 + SYSTEM_BUSY message/category, zero booking/slot mutation | script asserts + snapshots counts around call |
| T12 availability | same outage window, GET `/api/v1/resources/{id}/available-slots` | Merged cache-backed read MUST fall back to MySQL; script requires HTTP 200 + envelope code 0 + `data` present + zero DB mutation and records latency (else exit 2). | script result fields |

Missing student token ⇒ BLOCKED exit 3 before anything runs (the only blocked
precondition besides a stopped stack; DB access authenticates with the
container-created app account).

```powershell
$env:T13_STUDENT_TOKEN = '<runtime injected>'
pwsh deploy/scripts/redis-failure-check.ps1 -BaseUrl http://127.0.0.1
```

Missing `T13_STUDENT_TOKEN` ⇒ BLOCKED exit 3 (nothing touched) — DB access
authenticates with the container-created app account, so no host DB env is
needed. Non-local
BaseUrl ⇒ refused exit 2 before any request. `finally` always attempts redis
restart plus a `redis-cli ping` recovery gate.

## 5. Rollback doctrine

* Images/config rollback, **volumes preserved**: pin prior digest refs in
  `deploy/.env` (see README digest procedure), `compose up -d --force-recreate`.
  Never delete named volumes as a rollback step; restore data from a verified
  backup instead (lane 2 output).
* Migrations are forward-only. V001–V005 are owner-frozen; no rollback edits
  them. Data-level recovery = restore backup into place, not schema reversal.
* After any rollback rerun health smoke + one representative business flow
  before declaring recovery.

## RPO / RTO operator fields

Current local drill assumptions are RPO 24 hours and RTO 4 hours; the measured
restore was 2.131 seconds and passed the 14,400-second threshold; the run restored
one booking and two slot rows with matching aggregates. These are local exercise
assumptions, not a production SLA.

| Field | Value owner | Evidence link |
|---|---|---|
| Acceptable data-loss window (RPO assumption) | operator | `artifacts/t13-backup-restore-20260901-nonzero/result.json` (24 hours) |
| Measured restore duration (lane B) | auto | same result (2.131 seconds) |
| Acceptable recovery window (RTO assumption) | operator | same result (4 hours / 14400 seconds) |
| Restart persistence proof (lane C) | auto+operator review | `artifacts/t13-restart-persistence-20260901-audited/post-state.txt` |

## Stop conditions (halt and request owners)

1. Any lane exit ≠ 0 that indicates contract drift (missing unique key, schema
   mismatch) — open T-owner change request; T13 does not edit migrations.
2. Stack refuses to reach healthy within timeouts repeatedly.
3. Secret leakage detected in artifacts despite Redact scrubbing — purge the
   artifact set and rotate exposed material before continuing.
4. Redis fails to recover in lane D finally-path — immediate operator action.

## Raw evidence index (to be filled at apply time)

| Run | Lane | Artifacts dir | Verdict |
|---|---|---|---|
| `t13-empty-migration-20260901-final` | empty migration | `deploy/artifacts/t13-empty-migration-20260901-final/` | PASS |
| `t13-backup-restore-20260901-nonzero` | backup/restore | `deploy/artifacts/t13-backup-restore-20260901-nonzero/` | PASS |
| `t13-restart-persistence-20260901-audited` | restart persistence | `deploy/artifacts/t13-restart-persistence-20260901-audited/` | PASS |
| `t13-redis-outage-20260901-final` | Redis outage | `deploy/artifacts/t13-redis-outage-20260901-final/` | PASS |
