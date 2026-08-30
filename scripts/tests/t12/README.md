# T12 supporting-capabilities acceptance

Run from the worktree root:

```powershell
.\scripts\tests\t12\run.ps1 -Mode Check
.\scripts\tests\t12\run.ps1 -Mode List
.\scripts\tests\t12\run.ps1 -Mode OperationLog
.\scripts\tests\t12\run.ps1 -Mode Cache
.\scripts\tests\t12\run.ps1 -Mode RealCache
.\scripts\tests\t12\run.ps1 -Mode Notifications
.\scripts\tests\t12\run.ps1 -Mode Statistics
.\scripts\tests\t12\run.ps1 -Mode Frontend
.\scripts\tests\t12\run.ps1 -Mode Unit
```

`RealCache` requires a private Redis endpoint through `REDIS_HOST` and optional
`REDIS_PORT`/`REDIS_PASSWORD`; its Cache Aside integration case additionally
requires `RESOURCE_MYSQL_URL`/`RESOURCE_MYSQL_USERNAME`/
`RESOURCE_MYSQL_PASSWORD` (or the `DB_*` equivalents). It deliberately fails
instead of skipping when an endpoint is unavailable. The mode verifies the exact key, real
MISS/write/HIT behavior, deterministic 300–900 second expiry, commit-only
invalidation, rollback preservation, DB recalculation after invalidation,
availability fallback during a real Redisson outage, T07 lock fail-closed
behavior, and adapter containment after a real Redisson client is shut down.

`Cache` and `Unit` explicitly exclude the `real-redis` tag, so they remain
deterministic pure/narrow checks. They are not substitutes for `RealCache`.

`Frontend` runs the notification/statistics Node contract suite and then the
production `npm run build`; it requires the pinned `booking-web` dependencies
to be installed.
