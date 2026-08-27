## Tasks

- [x] 1.1 Before apply, read the same-wave reviewed `add-identity-access` identity contract (from the peer worktree/PR) and record the register 201 UserView (no auto-login), login 200 token contract, and me UserView for mock/UI/session/router implementation.
- [x] 1.1 post-merge: after T02 merges, re-read authoritative main spec and reconfirm exact fields/statuses/envelopes; revise artifacts/code/tests if changed.
- [x] 1.2 Record baseline status and enforce allowed paths; no package, main, contracts, resource or booking edits.
- [x] 1.3 Define auth API/store and optional `src/api/authMock.js` exact dispatcher, explicit unknown HTTP 404/code40400 envelope, demo-only in-memory accounts, no password persistence/logging.
- [x] 2.1 Implement real `/api/v1`, mock zero-network, request-time Bearer and redacted errors in `api/http.js`.
- [x] 2.2 Implement auth endpoints and frozen envelope normalization in `api/auth.js`.
- [x] 2.3 Implement `{token,tokenType,expiresAt}` session, computed early-expiry skew, shared hydrate `/users/me`, in-memory user, one-shot 401, forbidden-state reset after permitted navigation/logout, and safe redirect attack rejection in store.
- [x] 2.4 Add pure Node checks/browser probes for storage cleanup, injection, no secret logs, concurrent 401 and 403 preservation without dependencies.
- [x] 3.1 Preserve exactly twelve route records; global `beforeEach` handles `to.matched.length===0`, metadata guards, and role defaults without a catch-all record or error routes.
- [x] 3.2 Build authenticated layout/navigation and in-layout forbidden state; reset it after permitted navigation/logout; no `views/errors/**`.
- [x] 3.3 Keep App.vue router/layout entry only.
- [x] 4.1 Build Login/Register with exact T02 validation, accessible errors/loading/dedup, generic credentials and duplicate messages.
- [x] 5.1 Run `npm run build`, `git diff --check`, scope/secret checks.
- [x] 5.2 Agent-browser/Vite mock: hydrate, concurrent401, login401 no loop, forbidden state, safe redirect attacks, all twelve paths, zero network, screenshots.
- [x] 5.3 Real browser integration is a PR Ready gate only after T02 is merged and this change is rebased; verify `/api/v1`, Bearer, me, 401/403, and exact field compatibility.
- [x] 6.1 Run `openspec validate add-web-auth-shell --type change --strict --no-interactive` and require exit 0 with no strict warnings.
- [x] 6.2 After T02 merge/rebase and all implementation/tests are complete, use the OpenSpec sync skill (never invent `openspec sync`) to update main specs, run strict validation plus diff/status checks with `--no-interactive`, then archive only after acceptance gate; Draft PR must include scope, evidence, screenshots, and secrets check.
