## T01 Foundation gate

- [x] Confirm merged `extend-backend-data-security-foundation` exposes composable persistence/Jackson/security extension points; if not, pause and request shared-file scope.

## T02 Auth and user implementation (allowed paths only)

- [x] Add entities/enums/mappers/DTOs/VOs/services/controllers/principal under `booking-api/src/main/java/com/yu030x/booking/auth/**` and `.../user/**`; keep SecurityFilterChain, JWT filter/encoder/decoder in `auth/security/**`.
- [x] Implement exact register/login/profile/admin contracts, validation, UserView mapping, logical deletion semantics, duplicate-key translation, BCrypt-12 and dummy hash check; PATCH path is `/api/v1/admin/users/{id}/status`, and strict unknown JSON rejection covers RegisterRequest, LoginRequest, PUT me, and PATCH status (GET query extras ignored).
- [x] Implement HS256 env secret/TTL validation, claims, skew, strict Bearer parser, disabled-user recheck, ADMIN method rules, principal accessor, and `expiresIn=exp-iat` actual configured TTL (default 7200, 1..86400).
- [x] Define T02 security chain as `@Order(2)` for `/api/v1/**`, after health `@Order(1)` and before fallback; docs auth, if needed, uses a separate auth/security owner chain and does not modify common.

## T03 Tests and verification

- [x] Add real MySQL 8 integration tests for constraints, concurrent duplicate, deleted username reuse, defaults, status transitions; no “or equivalent”.
- [x] Add MockMvc exact JSON/error tests, unknown fields (40000/data null) for all four JSON DTOs, query-extra ignore, JWT clock/tamper/malformed cases, disabled old token, 401/403, pagination ordering/bounds, idempotency and self-disable; verify login invalid-credentials uses `账号或密码错误` while Bearer unauthenticated uses `unauthenticated`, and verify expiresIn against exp-iat.
- [x] Run `mvn verify` from `booking-api`, `git diff --check`, and verify all changes stay in T02 paths.

## T04 Spec and integration gate

- [x] Run `openspec validate add-identity-access --type change --strict`.
- [x] Use the `openspec-sync-specs` workflow (CLI has no `openspec sync`) to semantically sync the identity delta into `openspec/specs/identity-access/spec.md`, then validate main specs with `openspec validate --specs --strict`.
- [x] Archive the completed change.
- [ ] Finish the Draft PR workflow with dependency, rollback, and real联调 evidence.
