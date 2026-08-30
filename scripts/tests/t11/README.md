# T11 admin operations acceptance

Run from the worktree root:

```powershell
.\scripts\tests\t11\run.ps1 -Mode Check
.\scripts\tests\t11\run.ps1 -Mode List
.\scripts\tests\t11\run.ps1 -Mode Unit
.\scripts\tests\t11\run.ps1 -Mode Smoke
.\scripts\tests\t11\run.ps1 -Mode Browser
```

`Browser` requires the real backend at `http://127.0.0.1:18081`, the T11 Vite server at `http://127.0.0.1:4174`, healthy MySQL/Redis, and the local MySQL container. It creates only `t11qa_*` users plus fixed resource/booking IDs in the validation database, then captures redacted network logs, screenshots, a report, and `summary.json` under the ignored `artifacts/run-*` directory. A `PASS` file is written only when every case passes and no native browser dialog opens.

Optional environment variables:

- `T11_QA_FRONTEND`, `T11_QA_BACKEND`
- `T11_QA_MYSQL_CONTAINER` (default `campus-booking-validation-mysql-1`)

The browser suite verifies STUDENT zero-request route denial and shared 403 preservation, ADMIN user filtering/status/self-disable conflict, pending approval approve/reject flows, 404/409 boundaries, shared 401 clearing, refresh behavior, and in-layout confirmations without popups.
