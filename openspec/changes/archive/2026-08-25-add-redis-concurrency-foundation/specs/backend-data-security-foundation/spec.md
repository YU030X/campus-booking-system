## MODIFIED Requirements

### Requirement: Pinned backend foundation dependencies

The backend build MUST include `spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server`, MyBatis-Plus Boot 3 starter and jsqlparser 3.5.17, MySQL Connector/J, and springdoc 2.8.17 compatible with Spring Boot 3.5.4; Security/OAuth2 JOSE/MySQL use Boot BOM-managed fixed versions, with no dynamic/SNAPSHOT. The shared Redis foundation MAY additionally include `spring-boot-starter-data-redis` resolved by the Spring Boot 3.5.4 BOM and direct `org.redisson:redisson` pinned to `4.6.1`, but only behind the explicitly opt-in, validated configuration contract; no uncontrolled Redis/Redisson auto-configuration, Flyway, or business starter is allowed.

#### Scenario: Dependency audit

- **WHEN** `mvn dependency:tree` is run
- **THEN** required starters and the Redis foundation resolve to fixed, documented compatible versions, including the Boot 3.5.4 BOM and direct Redisson 4.6.1, with no SNAPSHOT or floating version; Redis/Redisson appear only for this shared foundation, and Flyway or business starters do not appear.
