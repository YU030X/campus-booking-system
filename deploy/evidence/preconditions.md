# T13 Preconditions & Ownership Evidence (slice 5)

Status marker legend: ✅ evidence complete · 🟡 partial (gate NOT satisfied) ·
⬜ not applicable to this slice.

## 1. Baseline & worktree (task 1.1) ✅

* Exact baseline commit recorded by this change: `0e53b7efec27f5821056bd546b8a245144414fdb`
  (task 1.1 / tasks.md:3). The originally requested ref name `main0e53b7e` did
  not exist; this is recorded as a missing REF NAME, never reinterpreted as a
  different commit.
* Dedicated worktree (absolute path):
  `D:\Projects\project1_campus\target\worktrees\verify-and-deploy-system`
* Branch: `codex/verify-and-deploy-system`

## 2. Integration merge evidence (task 1.2) 🟡 partial

Exact merge commits present on this branch (recorded, verified by `git log`
during the slice-5 planning session):

| Commit | Integrates |
|---|---|
| `1753903` | main |
| `652513b` | T09 (carrying T07 booking-lock + T10 work) |
| `240a7b2` | T08 |
| `974a213` | T11 |
| `13c51e0` | T12 |

Why the gate is still NOT satisfied (per tasks.md:4):

1. These commits are branch-local: **not merged into `main`**.
2. **Delta specs are not synced** into main specs (openspec sync pending).
3. Full **owner rebase/merge proof** per task has not been collected/indexed.

Consequence: the change stays **Draft**; execution-oriented gates may only
produce planning evidence (see `deploy/e2e/`, `deploy/jmeter/`, `deploy/demo/`
"never executed" banners).

## 3. T04–T12 owner/path index (task 1.2 input)

Recorded from existing `openspec/changes/archive/*` names and `codex/*`
branches on this worktree. T-numbers for later lanes follow the merge table
above and `docs/16-AI并行开发任务计划.md`; where a branch-to-T mapping is not
attested by merge evidence it is listed by branch name only.

| Area | Branch / archive change | State on this worktree |
|---|---|---|
| Backend foundation + identity | `codex/add-identity-access` (archive `2026-08-13-add-identity-access*`) | merged history present |
| Backend data security extension | `codex/extend-backend-data-security-foundation` (archive `2026-08-13-extend-backend-data-security-foundation*`) | merged history present |
| Resource catalog / foundation | `codex/add-resource-*`, archive `2026-08-13-add-resource-*`, `2026-08-13-initialize-*` | merged history present |
| Redis concurrency foundation (T-sibling) | `codex/add-redis-concurrency-foundation` | merged into main history |
| T08 resource catalog lane | integrated by `240a7b2` | branch-local (see §2) |
| T09 lane (incl. T07 booking-lock + T10) | integrated by `652513b` | branch-local (see §2) |
| T11 lane | integrated by `974a213` | branch-local (see §2) |
| T12 optional cuts: statistics → notifications → cache | integrated by `13c51e0`; cuts remain OPTIONAL and are not P1 prerequisites | branch-local (see §2) |
| Booking core / approval / check-in / availability / user-credit / supporting / web ×4 | `codex/add-concurrent-booking-core`, `codex/add-booking-approval-cancellation`, `codex/add-checkin-no-show-violation`, `codex/add-resource-availability`, `codex/add-user-credit-port`, `codex/add-supporting-capabilities`, `codex/add-web-auth-shell`, `codex/add-web-resource-management`, `codex/add-web-student-booking`, `codex/add-web-admin-operations` | planning artifacts present; NOT merged to main |

## 4. T13 ownership map (task 1.3) ✅

**T13 may write ONLY:**

* `deploy/**` — deployment, runbooks, verification scripts, evidence, demo.
* Integration/E2E test ownership paths under `deploy/e2e/**`.
* JMeter assets under `deploy/jmeter/**`.
* Deployment/performance/runbook/demo documentation within `deploy/**`.
* `openspec/changes/verify-and-deploy-system/tasks.md` progress notes.

**T13 must NEVER write:** business/backend/frontend/common source, `pom.xml`,
`package.json`/lockfiles, `sql/` migrations, root shared config, `docs/`,
existing `scripts/tests/**` (including T08), or any T01–T12 owned file.

## 5. Stop-and-request template (defect or shared-file change)

```
OWNER CHANGE REQUEST
owner task   : <Txx / repo area owner>
path         : <repo-relative path>
file:line    : <file>:<line>  (exact evidence)
contract     : <frozen contract clause at stake>
repro        : <minimal reproduction steps / failing gate>
evidence     : <artifact paths under deploy/artifacts/**, redacted>
blocked gate : <T13 task ids + gate that cannot proceed>
request      : <what the owner should change; T13 makes NO edit itself>
```

Live requests are filed in `deploy/owner-change-requests.md` (OCR-1 … OCR-8).

## 6. Runner selection (task 1.4) 🟡 partial

Selected and documented: repo-internal `scripts/tests/t08/run.ps1` (`-Action
Run`) + Chrome `--headless=new` over raw CDP — the only in-repo browser
harness (rationale: `deploy/e2e/README.md`). The apply-environment
AVAILABILITY check (Chrome/node actually present) has NOT been executed, so
task 1.4 remains unchecked.
