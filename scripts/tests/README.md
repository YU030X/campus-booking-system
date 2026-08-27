# Reusable Test Scripts

Single documented entry per scope (`run.ps1`, Windows-first).

| Scope | Entry | Modes |
| --- | --- | --- |
| t11 (add-web-admin-operations) | `scripts/tests/t11/run.ps1` | `Check` boundary+diff check · `List` case list · `Unit` node --test · `All`(default) |

Usage:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/tests/t11/run.ps1 -Mode All
```

Run logs land in the scope-local `artifacts/` directory which is git-ignored.
