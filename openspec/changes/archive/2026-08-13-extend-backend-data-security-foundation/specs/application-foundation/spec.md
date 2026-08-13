## MODIFIED Requirements

### Requirement: Backend foundation is runnable
The backend MUST target JDK 17, use Spring Boot 3.5.x, package code as `com.yu030x.booking`, and contain only entrypoint plus `common.api`, `common.exception`, and `common.config` in T01 and this foundation change. T01 and foundation backend paths are restricted to `booking-api/pom.xml`, `booking-api/src/main/java/com/yu030x/booking/**`, `booking-api/src/main/resources/application.yml`, and `booking-api/src/test/java/com/yu030x/booking/**`; no business directories, entities, mappers, or seed modules are allowed. The runtime MUST expose a health endpoint that remains accessible without authentication while all non-health future API paths remain protected by default security behavior.

#### Scenario: Health smoke
- **WHEN** `cd booking-api && mvn spring-boot:run` is started with externally provided `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`, and `/actuator/health` (or documented health endpoint) is requested
- **THEN** it reports healthy without secrets, seed accounts, feature modules, or a generated default account, and requests to non-health API paths are not implicitly public.

### Requirement: Frontend foundation is buildable
The frontend MUST be JavaScript using Vue 3 Composition API, Vite, Element Plus, Vue Router, Pinia, and Axios. T01 MUST create only shell, constants, contracts, and routes; allowed frontend paths are `booking-web/package.json`, `booking-web/package-lock.json`, `booking-web/index.html`, `booking-web/vite.config.js`, `booking-web/src/main.js`, `booking-web/src/App.vue`, `booking-web/src/api/http.js`, `booking-web/src/api/contracts.js`, `booking-web/src/types/contracts.js`, and `booking-web/src/router/index.js`. Safe placeholders stay in `App.vue`; no `views/` or other business directories may be added. `VITE_API_MODE` MUST accept only `mock|real`.

#### Scenario: Build shell
- **WHEN** `cd booking-web && npm install && npm run build` runs on Node LTS
- **THEN** Vite emits a production bundle without feature pages or backend endpoints.

### Requirement: Safe configuration defaults
Committed configuration MUST contain placeholders only, Asia/Shanghai settings, and no credentials. Flyway MUST NOT be introduced. Database connectivity MUST be env-only through `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`; no repository default may supply `JWT_SECRET`, passwords, or signing keys. Springdoc exposure MUST be explicitly configurable and may be disabled for production. Foundation MUST not require `JWT_SECRET` or create JWT signing capability.

#### Scenario: Inspect configuration
- **WHEN** committed configuration is inspected
- **THEN** only placeholders and environment references exist, timezone is Asia/Shanghai, no Flyway dependency or secret is present, and API documentation exposure is controlled by configuration.
