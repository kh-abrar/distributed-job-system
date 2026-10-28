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

FROM eclipse-temurin:17-jre AS api
WORKDIR /app
COPY --from=build /src/api/target/api-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
# Exec form, so java is PID 1 and receives SIGTERM directly
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:17-jre AS worker
WORKDIR /app
COPY --from=build /src/worker/target/worker-0.1.0-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
