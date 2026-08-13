## 1. Dependency and configuration foundation

- [x] 1.1 Update only `booking-api/pom.xml` with Security + OAuth2 resource-server, MyBatis-Plus Boot 3 starter/jsqlparser `3.5.17`, MySQL Connector/J, and springdoc `2.8.17`; verify Boot 3.5.4 BOM-managed versions and no dynamic/SNAPSHOT/Redis/Redisson/Flyway/business dependencies using `mvn dependency:tree`.
- [x] 1.2 Update only `booking-api/src/main/resources/application.yml` with env-only `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, Asia/Shanghai, MyBatis camel-case/logical-delete/pagination, an optional nullable JWT secret placeholder with no validation/decoder/signing, health exposure, and configurable springdoc; ensure no credentials or H2 fallback are committed.

## 2. Common configuration and tests

- [x] 2.1 Add only new files under `booking-api/src/main/java/com/yu030x/booking/common/config/**` for datasource/MyBatis, `@Order(1)` health-only security chain, lowest-priority `AnyRequestMatcher.INSTANCE` fallback deny/401 chain, and springdoc properties; JWT properties are optional only if needed (no decoder/signing); do not add business modules, entities, mappers, SQL, or API controllers. Document that T02 must add `@Order(2)` `/api/v1/**` application chain (optionally covering docs paths) between health and fallback.
- [x] 2.2 Add matching tests only under `booking-api/src/test/java/com/yu030x/booking/common/config/**` asserting health returns 200, random and `/api/**` requests return 401/403 and never 200, docs are 404 when disabled or rejected by fallback, no `UserDetailsService` default user/generated password, no required JWT secret/decoder/signing, env-only secret handling, mapper settings, and springdoc switch; do not add `application-test.yml` unless this task first lists it.

## 3. Verification and handoff gates

- [x] 3.1 Run `cd booking-api && mvn dependency:tree` and `mvn verify`; record exact results and inspect logs for default passwords, JWT defaults, H2, Redis, Flyway, or seed data.
- [x] 3.2 With externally provisioned MySQL 8 and `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` set outside the repository, start the service and probe `/actuator/health`; also prove health starts without `JWT_SECRET`, no JwtDecoder/signing capability exists, no secrets/default password are logged, and no schema migration/seed runs.
- [x] 3.3a Run `openspec validate extend-backend-data-security-foundation --type change --strict --no-interactive`.
- [x] 3.3b Run `git diff --check`.
- [x] 3.3c Verify `git status --short` contains only this change's artifacts/code paths.
- [x] 3.3d Sync delta specs to the corresponding main specs and verify the main specs are fully synced.
- [x] 3.3e Archive the completed change under `openspec/changes/archive/2026-08-13-extend-backend-data-security-foundation`.
- [ ] 3.3f Open a Draft PR after archive completion.
