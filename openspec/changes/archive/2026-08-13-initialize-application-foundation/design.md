## Context

T01 is the single writer for foundation build/config, hand-authored SQL, and shared API/frontend contracts. It creates no feature implementation.

## Goals / Non-Goals

Goals: reproducible Spring/Vue shells, deterministic twelve-table schema, and explicit shared contracts. Non-goals: Flyway, seed/demo users, authentication, business transactions, CRUD, deployment, or pages.

## Decisions

- Backend package is `com.yu030x.booking`; T01 provides only entrypoint plus `common.api`, `common.exception`, and `common.config`.
- SQL is exactly V001–V005, manually ordered. Apply each sequence to two separate fresh empty MySQL 8.0 databases and compare schema/index results; same-database rerun is not required.
- Tables use the complete docs/11 DDL (all twelve fields/defaults/indexes), InnoDB/utf8mb4, no physical foreign keys, `password VARCHAR(100)`, and no business seed; V005 is a post-seed placeholder comment.
- Frontend is JavaScript (not TypeScript), Vue 3 Composition API, Vite, Element Plus, Router, Pinia, Axios; API mode is `VITE_API_MODE=mock|real`; frozen `/api/v1` routes are `/login,/register,/resources,/resources/:id,/bookings,/bookings/:id,/admin/categories,/admin/resources,/admin/rules,/admin/closures,/admin/approvals,/admin/users`. T04 implements auth pages/store/API/guards.
- Long identifiers serialize as strings; times use `yyyy-MM-dd HH:mm:ss` and Asia/Shanghai.

## Ownership / Integration

Task entries name exact allowed paths and one writer. T01 backend changes are limited to `booking-api/pom.xml`, `booking-api/src/main/java/com/yu030x/booking/**`, `booking-api/src/main/resources/application.yml`, and `booking-api/src/test/java/com/yu030x/booking/**`; frontend changes are limited to `booking-web/package.json`, `booking-web/package-lock.json`, `booking-web/index.html`, `booking-web/vite.config.js`, `booking-web/src/main.js`, `booking-web/src/App.vue`, `booking-web/src/api/http.js`, `booking-web/src/api/contracts.js`, `booking-web/src/types/contracts.js`, and `booking-web/src/router/index.js`. Safe frontend placeholders stay inside `App.vue`; no other business directories or `views/` may be added. SQL remains limited to the paths in task 3.1. T01 freezes contract constants and shells only; T04 may extend auth API/store/router without implementing pages.

## Validation

Run backend health smoke, frontend build, both fresh-database migration runs with table/index assertions, contract-level tests, `openspec validate initialize-application-foundation --type change --strict`, and `git diff --check`.

## Risks / Rollback

Contract drift or schema mismatch blocks downstream work; mitigate with strict validation and index/schema assertions. Roll back by abandoning the change before merge; after adoption use a new change, never rewrite applied migrations.
