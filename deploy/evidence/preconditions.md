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

## 2. Integration merge and spec-sync evidence (task 1.2) ✅

Exact merge commits present on this branch (recorded, verified by `git log`
during the slice-5 planning session):

| Commit | Integrates |
|---|---|
| `1753903` | main |
| `652513b` | T09 (carrying T07 booking-lock + T10 work) |
| `240a7b2` | T08 |
| `974a213` | T11 |
| `13c51e0` | earlier T12 snapshot |
| `19649b5` | current accepted T12/T07/T09/T10/T11 chain |
| `070155f` | synchronized discovered T04–T12 delta capabilities into main specs |

`git merge-base --is-ancestor main HEAD` succeeds, and the dedicated T13 branch
contains the accepted owner commits plus current T12 merge. Main-spec sync
created/updated every delta path reported by `openspec status`; strict main-spec
validation passed 21/21. T12's ordered statistics→notifications→cache cut matrix
passed three complete 82/82 booking/T07 runs. The change remains Draft for
runtime, fixture/history, digest, and external gates—not for missing dependency
integration or spec sync.

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
| T08 student frontend lane | integrated by `240a7b2` | merged into T13 integration branch |
| T09 lane (incl. T07 booking-lock + T10) | integrated by `652513b` and current T12 owner chain | merged into T13 integration branch |
| T11 lane | integrated by `974a213` and current T12 owner chain | merged into T13 integration branch |
| T12 optional cuts: statistics → notifications → cache | current chain integrated by `19649b5`; cuts remain OPTIONAL and are not P1 prerequisites | merged; CutMatrix 3×82 passed |
| Booking core / approval / check-in / availability / user-credit / supporting / web ×4 | accepted owner branches indexed by the T12 handoff and merge history | implementation integrated; discovered delta specs synced by `070155f` |

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

## 6. Runner selection (task 1.4) ✅

Selected and documented: repo-internal `scripts/tests/t08/run.ps1` (`-Action
Run`) + Chrome `--headless=new` over raw CDP — the only in-repo browser
harness (rationale: `deploy/e2e/README.md`). The recorded availability command
exited zero with `CHECK_OK` in
`deploy/artifacts/local-runner-check/runner-check.log`; this proves runner
availability only, not a browser flow.
