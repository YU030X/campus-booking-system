## Purpose

Defines the shared persistence and security foundation required by later booking modules without implementing business behavior.

## ADDED Requirements

### Requirement: Pinned backend foundation dependencies
The backend build MUST include `spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server`, MyBatis-Plus Boot 3 starter and jsqlparser 3.5.17, MySQL Connector/J, and springdoc 2.8.17 compatible with Spring Boot 3.5.4; Security/OAuth2 JOSE/MySQL use Boot BOM-managed fixed versions, with no dynamic/SNAPSHOT.

#### Scenario: Dependency audit
- **WHEN** `mvn dependency:tree` is run
- **THEN** all required starters resolve to the documented pinned versions, with no SNAPSHOT or floating version and no Redis, Redisson, Flyway, or business starter.

### Requirement: Environment-only MySQL and mapper defaults
The runtime MUST read MySQL URL, username, and password only from `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`; it MUST use Asia/Shanghai, map-underscore-to-camel-case, logic-delete field `deleted` with value `1`/not-delete `0`, and `PaginationInnerInterceptor(DbType.MYSQL)` maxLimit `100`, overflow `false`, without requiring seed data.

#### Scenario: External database startup
- **WHEN** the service starts against an externally provisioned MySQL 8 instance with those variables set
- **THEN** datasource and mapper infrastructure initialize without committed credentials, embedded/H2 substitution, or schema migration execution.

### Requirement: Security boundary and documentation exposure
The service MUST not create a generated default user or password. An `@Order(1)` stateless health-only chain matched by `EndpointRequest.to(HealthEndpoint.class)` (or exact `/actuator/health/**`) MUST permit health and disable csrf/form/basic without `anyRequest`. A lowest-priority fallback chain using `securityMatcher(AnyRequestMatcher.INSTANCE)` MUST deny all unmatched requests or return 401. T02 owns an `@Order(2)` `/api/v1/**` application chain (registration/login permitAll, other routes authenticated) and may include `/v3/api-docs/**`, `/swagger-ui.html`, and `/swagger-ui/**`, or provide a separate docs chain at that order; foundation MUST not permit docs. Foundation MUST not bind a required `JWT_SECRET`, create JwtDecoder/encoder, or claim JWT signing; a nullable placeholder has no validation/decoder/signing and T02 requires >=32-byte secret. Springdoc MUST use explicit switches defaulting false and remain authenticated when enabled.

#### Scenario: Security defaults
- **WHEN** the application starts with no JWT secret and no credentials in repository configuration
- **THEN** no default account/password is logged or provisioned, no `UserDetailsService` default bean or JwtDecoder exists, and no JWT secret is required; health returns 200 while random/API requests return 401/403 (never 200), and docs are 404 when disabled or rejected by fallback.
