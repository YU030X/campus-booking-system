## Context

See proposal.md and the existing `application-foundation`, `data-schema`, and `shared-contracts` specs. T01 currently has a minimal Spring Boot 3.5.4 POM and actuator-only YAML; T02/T03 must not edit those shared files.

## Goals / Non-Goals

**Goals:** establish one auditable dependency/configuration owner; make MySQL 8 and MyBatis-Plus deterministic; preserve anonymous health while avoiding Spring Security's generated default account; provide a production-disableable springdoc surface; document external validation.

**Non-Goals:** auth/user/resource/booking code, SQL migrations or entities, Redis/Redisson, Flyway, seed data, frontend work, or changes to API/error contracts.

## Decisions

1. Use Spring Boot parent/BOM 3.5.4. Pin `com.baomidou:mybatis-plus-spring-boot3-starter:3.5.17`, `com.baomidou:mybatis-plus-jsqlparser:3.5.17`, and `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17`; Security/OAuth2 JOSE/MySQL remain BOM-managed. MP 3.5.9+ makes parser optional, so the parser is explicit; springdoc 2.8.x targets Boot 3.5. No dynamic/SNAPSHOT versions.
2. Use `${DB_URL}`, `${DB_USERNAME}`, `${DB_PASSWORD}` with no fallback credentials. Set `serverTimezone=Asia/Shanghai`/JVM Jackson timezone as needed, and keep schema initialization/migration disabled. MyBatis-Plus config enables underscore-to-camel mapping, logical delete values, and a bounded pagination interceptor without introducing domain mappers.
3. Compose `spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server`; resource-server supplies JOSE and backs off Boot's default `UserDetailsService`. Define `@Order(1)` health-only chain matched only by `EndpointRequest.to(HealthEndpoint.class)` (or exact `/actuator/health/**`), stateless, permitAll, csrf/form/basic disabled, with no `anyRequest`. Define a lowest-priority fallback chain using `securityMatcher(AnyRequestMatcher.INSTANCE)` and `anyRequest().denyAll()` (or an equivalent 401 entry point). T02 must provide `@Order(2)` application chain matching `/api/v1/**` (and, if desired, `/v3/api-docs/**`, `/swagger-ui.html`, `/swagger-ui/**`) between these chains; registration/login are permitAll and other matched routes authenticated. Never exclude SecurityAutoConfiguration or permit-all globally.
4. Do not create JwtDecoder/encoder here. Reserve nullable `booking.security.jwt-secret: ${JWT_SECRET:}` as a placeholder only—no validation, decoder, signing, or required binding; T02 requires >=32-byte secret and signing capability. Gate docs with `springdoc.api-docs.enabled=${SPRINGDOC_ENABLED:false}` and `springdoc.swagger-ui.enabled=${SPRINGDOC_ENABLED:false}`. Before T02's chain exists, docs are disabled (404) or rejected by fallback; T02 must authenticate docs when enabled via its application matcher or a separate `@Order(2)` docs chain.
5. Verification uses `mvn dependency:tree`, `mvn verify`, `openspec validate ... --strict --no-interactive`, `git diff --check`, and a startup/health probe against an externally provisioned MySQL 8. Maven Central artifact pages and Spring Boot dependency-management docs are recorded as primary compatibility evidence during implementation.

## Risks / Trade-offs

- [Risk] A datasource is unavailable in a no-DB local smoke test → Mitigation: health-only tests use a test profile with datasource auto-configuration explicitly controlled; acceptance includes an external MySQL 8 run, never H2 masquerading as MySQL.
- [Risk] A narrow chain could conflict with T02's application chain → Mitigation: keep it in `common/config`, document matcher/order ownership, and require T02 to add a higher-priority application chain without broad permit-all rules.
- [Risk] springdoc transitive versions drift → Mitigation: fixed starter version plus dependency-tree evidence in CI.

## Migration Plan

Apply dependency/config changes, run tests and external-MySQL health validation, then let T02/T03 consume the committed foundation. Rollback is reverting this single change; no database migration or data rollback is required.
