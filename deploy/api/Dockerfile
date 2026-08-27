# T13 deploy slice 1 - booking-api runtime image.
# Multi-stage JDK 17 build. Base tags are pinned (non-floating); each ARG can be
# overridden from deploy/.env with a fully qualified digest reference, see README.md
# ("Image digest update procedure"). Status: statically authored only; no image has
# been built and no digest recorded yet.

# Both ARGs sit before the first FROM so they are global-scope and safely
# substitutable in every subsequent FROM of this multi-stage build.
ARG MAVEN_BUILD_IMAGE=maven:3.9.9-eclipse-temurin-17
ARG JRE_RUNTIME_IMAGE=eclipse-temurin:17.0.12_7-jre-jammy

FROM ${MAVEN_BUILD_IMAGE} AS build

WORKDIR /workspace

# Fail closed if the Maven project is moved or renamed (jar name is asserted below).
COPY pom.xml ./
COPY src ./src

RUN mvn -B -DskipTests package

FROM ${JRE_RUNTIME_IMAGE}

# Non-root runtime user; no login shell, no password field.
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --shell /usr/sbin/nologin --home-dir /app app

WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/booking-api-*.jar /app/app.jar

USER 10001:10001
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
