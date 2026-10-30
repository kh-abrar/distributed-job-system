# syntax=docker/dockerfile:1

# Builds both services from source, so running the stack needs only Docker.
# Targets: "api" and "worker" (see docker-compose.yml).

FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY common/pom.xml common/
COPY api/pom.xml api/
COPY worker/pom.xml worker/
COPY common/src common/src
COPY api/src api/src
COPY worker/src worker/src
# The cache mount keeps downloaded dependencies between builds.
# Tests are skipped here: they need Docker themselves (Testcontainers); run them with mvn verify.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -Dmaven.test.skip=true

# Shared runtime base: both services run as an unprivileged user, not root.
# The jar stays owned by root, so the process can read its code but not change it;
# the apps only write temporary files, under /tmp.
FROM eclipse-temurin:17-jre AS runtime
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app

FROM runtime AS api
COPY --from=build /src/api/target/api-0.1.0-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080
# Exec form, so java is PID 1 and receives SIGTERM directly
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM runtime AS worker
COPY --from=build /src/worker/target/worker-0.1.0-SNAPSHOT.jar app.jar
USER app
ENTRYPOINT ["java", "-jar", "app.jar"]
