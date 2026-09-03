# T12 supporting capabilities handoff

## Status

Implementation and executable acceptance are complete for operation logging,
availability caching, in-app notifications, administrator statistics, their
owner mutation/event hooks, shared feature flags, and the P1 frontend pages.
The final planning precondition, task 0.1, was resolved on 2026-09-02 with
explicit user confirmation: the unreachable base pin `0e53b7e` was replaced by
the verified equivalent shared base `2ffae9d` in proposal/design/tasks, and the
task is checked. The change is 28/28.

## Accepted owner handoffs

- Shared configuration owner `a5b01fb` binds four independent opt-in flags to
  explicit default-false environment variables and adds their activation
  contract test.
- Shared frontend owner `c0e06f1` adds `/notifications` for authenticated roles
  and `/admin/statistics` for ADMIN, API mapping, loading/error/empty states,
  read actions, date validation, Node contracts, and the production build mode.
- Resource owner `89ebf53` and booking owner `e9df066` publish commit-only cache
  invalidation requests for every required mutation.
- Approval owner `88eb8cb` and no-show owner `e5ebc37` publish notification
  events only from winning business transitions; real-integration evidence is
  committed in `89607a9` and `12b476c`.

## Feature flags and rollback

| Capability | Spring property | Environment binding | Default / rollback |
| --- | --- | --- | --- |
| Operation log | `booking.operation-log.enabled` | `BOOKING_OPERATION_LOG_ENABLED` | `false` |
| Availability cache | `booking.cache.enabled` | `BOOKING_CACHE_ENABLED` | `false` |
| Notifications | `booking.notifications.enabled` | `BOOKING_NOTIFICATIONS_ENABLED` | `false` |
| Statistics | `booking.statistics.enabled` | `BOOKING_STATISTICS_ENABLED` | `false` |

Rollback is configuration-only: set the affected environment variable to
`false` and restart the API. Do not delete code, dependencies, or existing
support tables. Disabling cache affects only availability Cache Aside; the T07
booking lock retains its independent fail-closed Redis behavior.

## Acceptance evidence

- `mvn verify`: 387/387, 0 failures, 0 errors, 0 skipped, real MySQL 8 and Redis
  7 available; aggregate EXPLAIN output emitted; build success.
- `scripts/tests/t12/run.ps1 -Mode CutMatrix`: flag contracts 4/4, followed by
  three ordered booking/T07 runs (statistics off, then notifications off, then
  cache off), each 82/82 with no failure, error, or skip.
- `scripts/tests/t12/run.ps1 -Mode Frontend`: Node contracts 6/6 and Vite
  production build success.
- Cache: narrow 29/29 and real MySQL/Redis 5/5; notifications 35/35; statistics
  20/20; T11 shared frontend regression 66/66.
- Strict OpenSpec validation and `git diff --check` are required once more after
  this handoff/tasks update and are recorded in `tasks.md`.

## Risks and deliberately unrun checks

- The generated frontend JavaScript chunk is about 1.18 MB before gzip and
  triggers Vite's size warning; this is a performance follow-up, not a build
  failure.
- Notification/statistics browser E2E was not run. Their deterministic Node
  contracts and production build passed, but interactive browser evidence
  remains appropriate for T13 system acceptance.
- No latency benchmark is claimed for cache behavior; acceptance proves the
  observable MISS/write/HIT/invalidate state sequence only.
- Docker Desktop remains unsuitable for this run because of a stale local
  socket. Acceptance used the existing MySQL 8.0.46 and Redis 7.0.15 services
  in Ubuntu-24.04 WSL. No Docker images or volumes were removed.
- Production host, domain, DNS, TLS, and deployment credentials are outside
  T12 and remain T13 inputs.

## Spec sync / archive instructions

1. Obtain explicit confirmation to replace the unreachable task-0.1 base pin
   with the verified equivalent shared base, then update and check task 0.1.
2. Run strict change validation and final acceptance again if implementation
   changes after this handoff.
3. Review the delta specs, sync them to main specs with the OpenSpec sync
   workflow, validate main specs strictly, and archive only after T13 confirms
   the integrated system. Do not push automatically.
