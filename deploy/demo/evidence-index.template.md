# T13 Demo Evidence Index — TEMPLATE (all placeholders; NOTHING has run)

> Every row below is a NOT RUN / DRAFT placeholder. Real runs must replace the
> artifact column with actual run-relative paths and update Status honestly.
> Approval-browser rows can never reach PASS in this slice (OCR-8).

| # | Requirement slice | Evidence artifact (run-relative) | Status |
|---|---|---|---|
| 1 | Deterministic fixture creation (users/category/resource/rule/pending booking) | `<fixture-map.json>` | NOT RUN / DRAFT |
| 2 | Owner seed reference inspected only (T08 seed.sql, never executed because it is destructive) | `<setup log entry in fixture-map.createdAt only>` | NOT RUN / DRAFT |
| 3 | PENDING booking via student API | `<fixture-map.json>.pendingBookingId` | NOT RUN / DRAFT |
| 4 | No-show/violation state via OWNER scan (past CONFIRMED seed labeled EPHEMERAL-SETUP-NOT-ACCEPTANCE-EVIDENCE) | `<fixture-map.json>.noShowState` | NOT RUN / DRAFT |
| 5 | Student browser direct flow (register/login/booking/conflict-refresh/cancel + slot release) | `<e2e-StudentBrowser-*/t08-copy/REPORT.md>` | NOT RUN / DRAFT |
| 6 | Network traces scrubbed (Authorization/Cookie/PII) | `<e2e-StudentBrowser-*/t08-copy/network.jsonl>` | NOT RUN / DRAFT |
| 7 | Screenshots | `<e2e-StudentBrowser-*/screenshots-unreviewed/>` | REQUIRES MANUAL VISUAL PII REVIEW — NOT PUBLISHED |
| 8 | Approval browser flow (admin approve/reject UI) | — | BLOCKED (OCR-8) — must never be marked PASS |
| 9 | Teardown scope-limited cleanup proof | `<teardown-evidence.txt>` | NOT RUN / DRAFT |
| 10 | Secret material lifecycle (temp JSON deleted, verified) | `<run console only — no artifact>` | NOT RUN / DRAFT |

Notes:
- Rows 1–4 are EPHEMERAL SETUP facts, not acceptance evidence by themselves.
- Row 5–7 become citable only after `deploy/e2e/run.ps1 -Mode StudentBrowser`
  actually runs and its redaction manifest reports zero residual.
- Row 8 stays BLOCKED until OCR-8 is resolved by the owning task.
