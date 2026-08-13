## Why

T01 establishes the runnable full-stack foundation and freezes contracts for downstream feature changes. It must be deterministic on a new database and must not smuggle business behavior, seed accounts, or authentication into the foundation.

## What Changes

- Add a Spring Boot 3.5.x/JDK 17 backend shell under `com.yu030x.booking` and a Vue 3/Vite/Element Plus JavaScript shell. T01 backend files are restricted to `booking-api/pom.xml`, `booking-api/src/main/java/com/yu030x/booking/**`, `booking-api/src/main/resources/application.yml`, and `booking-api/src/test/java/com/yu030x/booking/**`; frontend files are restricted to `booking-web/package.json`, `booking-web/package-lock.json`, `booking-web/index.html`, `booking-web/vite.config.js`, `booking-web/src/main.js`, `booking-web/src/App.vue`, `booking-web/src/api/http.js`, `booking-web/src/api/contracts.js`, `booking-web/src/types/contracts.js`, and `booking-web/src/router/index.js`. No other business directories or `views/` are permitted; safe placeholders remain in `App.vue`.
- Add V001–V005 hand-authored, ordered MySQL 8.0 SQL creating exactly twelve tables; no Flyway, no seed data, and no accounts.
- Freeze API envelope, pagination, complete error-code meanings, identifiers, time, roles, complete status transitions, and the docs/15 `/api/v1` API/router matrix.
- Define slot uniqueness and terminal-state physical release as schema/contracts; booking/score transactions remain later work.

## Non-Goals

Authentication/JWT implementation and business CRUD/workflows remain deferred; T04 owns login/register pages, auth store/API, and guards. T01 includes no business seed (V005 is an executable post-seed placeholder comment).

## Impact

T01 owns only its change artifacts and the foundation paths named in `tasks.md`. Downstream changes consume these contracts; breaking revisions require a new OpenSpec change. Validation includes two independent empty-database runs, strict OpenSpec validation, and `git diff --check`.
