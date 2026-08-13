## 1. Backend foundation (single writer: T01)

- [x] 1.1 Allowed paths: `booking-api/pom.xml`, `booking-api/src/main/java/com/yu030x/booking/**`, `booking-api/src/main/resources/application.yml`, and `booking-api/src/test/java/com/yu030x/booking/**` only; create Spring Boot 3.5.x/JDK17 shell only. No other backend business directories. Verify `cd booking-api && mvn verify`.
- [x] 1.2 Add health endpoint and smoke command `mvn spring-boot:run` plus documented curl check; no feature modules, Flyway, seed, or accounts.
- [x] 1.3 Add contract tests for envelope/page/Long/time/error/status/constants; run `mvn test`.

## 2. Frontend foundation (single writer: T01)

- [x] 2.1 Allowed paths: `booking-web/package.json`, `booking-web/package-lock.json`, `booking-web/index.html`, `booking-web/vite.config.js`, `booking-web/src/main.js`, `booking-web/src/App.vue`, `booking-web/src/api/http.js`, `booking-web/src/api/contracts.js`, `booking-web/src/types/contracts.js`, and `booking-web/src/router/index.js` only. Safe placeholder components MUST remain inside `App.vue`; do not add `views/` or other business directories. Use Node LTS, JS/Vue3 Composition/Vite/ElementPlus/Router/Pinia/Axios.
- [x] 2.2 Freeze `VITE_API_MODE=mock|real`, `/api/v1` API module matrix and exact route table; T01 only constants/contracts/safe placeholder routes. T04 adds auth API/store/pages/guards. Verify `npm install && npm run build`.

## 3. Database (single writer: T01)

- [x] 3.1 Allowed paths: `sql/V001__*.sql` through `sql/V005__*.sql` only. Create exactly twelve tables matching docs/11 complete fields/defaults/indexes; V001 database, V002 user/resource, V003 booking, V004 support, V005 executable post-seed placeholder only; no Flyway, FK, business seed, or credentials.
- [x] 3.2 Verify slot unique key, booking `(user_id,status)` plus other docs/11 indexes, `uk_booking_type`, blacklist `start_date/end_date` and `idx_user_end`, notification and operation docs/11 indexes/fields, InnoDB/utf8mb4, password VARCHAR(100), and lifecycle release rules.
- [x] 3.3 Apply V001–V005 to two independent empty MySQL 8.0 databases; compare table/index definitions. Same-database rerun is not required. Record commands/results.

## 4. Handoff and spec lifecycle (single writer: T01)

- [x] 4.1 Reconcile proposal/design/three delta specs and allowed paths; run `openspec validate initialize-application-foundation --type change --strict`.
- [x] 4.2 Sync delta to main specs, rerun strict validation, then archive change when complete.
- [x] 4.3 Run `git diff --check`; prepare a Draft PR with scope, validation, risks, rollback, and screenshots. Do not commit or push in T01 execution.
