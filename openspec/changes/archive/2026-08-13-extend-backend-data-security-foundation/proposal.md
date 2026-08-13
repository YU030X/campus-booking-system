## Why

Wave 2 auth, user, resource, and booking work currently has no shared persistence, security, or API documentation foundation. This T02/T03 prerequisite establishes env-only MySQL/MyBatis-Plus, a health-only Spring Security boundary, and disabled-by-default springdoc; JWT authentication/signing is deferred to T02 and T04 remains independent.

## What Changes

- Add `spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server`, MyBatis-Plus Boot 3 starter/jsqlparser 3.5.17, MySQL Connector/J, and springdoc 2.8.17; Spring Boot remains 3.5.4 and BOM-managed security/OAuth2 JOSE/MySQL versions are fixed (no dynamic/SNAPSHOT).
- Configure MySQL 8 via `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` environment variables, Asia/Shanghai timezone, MyBatis camel-case mapping, logical deletion, and pagination.
- Add two foundation chains: `@Order(1)` health-only `EndpointRequest.to(HealthEndpoint.class)` permitAll/stateless with csrf/form/basic disabled, plus a lowest-priority fallback using `securityMatcher(AnyRequestMatcher.INSTANCE)` that denies all unmatched requests (or returns 401). The health chain must not use `anyRequest` or swallow T02's `/api/v1/**` chain. Do not implement JWT signing here: reserve a nullable `JWT_SECRET` placeholder with no validation/decoder/signing and defer JWT to T02.
- Add springdoc switches defaulting false; enabled docs remain authenticated by T02.
- Add common/config tests and verification procedures; explicitly do not add Redis, Redisson, Flyway, entities, mappers, seed data, auth/user/resource business, SQL, frontend, or shared API/error contract changes. Before T02 merges, docs remain disabled and are rejected by fallback; T02 must place its `@Order(2)` application chain between health and fallback and either match `/api/v1/**` plus docs paths or add a separate docs chain.

## Capabilities

### New Capabilities

- `backend-data-security-foundation`: Shared dependency, datasource, persistence, security baseline, and configurable development documentation behavior for Wave 2.

### Modified Capabilities

- `application-foundation`: Extend the backend foundation requirement while retaining the existing JDK/package/path and health-smoke scenarios; define the new env-only persistence/security defaults and T01-compatible health access.

## Impact

- Owned paths: `booking-api/pom.xml`, `booking-api/src/main/resources/application.yml` (and only necessary test configuration), new `booking-api/src/main/java/com/yu030x/booking/common/config/**`, and matching common/config tests.
- T02 and T03 are gated on this change for shared POM/config ownership; T04 remains independent.
- Runtime requires an externally provisioned MySQL 8 instance and environment variables. No secrets, seed accounts, Redis, migration runner, or business modules are introduced.
