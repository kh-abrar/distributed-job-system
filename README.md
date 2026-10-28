# Distributed Job Processing System

A small job queue built to study the hard parts of distributed work: leases,
fencing, retries, idempotent submission and recovery from crashed workers.
Clients submit jobs over a REST API, PostgreSQL is the source of truth, and a
pool of workers claims and runs them with at-least-once execution.

This is a learning and portfolio project, not a production platform. The design
comes first: see the [requirements and design spec](docs/00-requirements.md) and
the [architecture decisions](docs/decisions/).

## Status

| Area | Spec | Status |
|---|---|---|
| Submit a job, with idempotency key | FR-1 to FR-6, G1, G4 | Done, tested |
| Fetch a job | FR-7, FR-8 | Done |
| Cancel a pending job | FR-11 to FR-13 | Done, tested |
| List jobs with cursor pagination | FR-9, FR-10 | Not started |
| Manually requeue a dead job | FR-27 | Not started |
| Worker: claim, lease, heartbeat, fencing, retry, reaper | FR-14 to FR-26, G2, G3, G5, G6 | Not started |

## Stack

Java 17, Spring Boot 3.2, PostgreSQL 16, Flyway, Testcontainers. Three Maven
modules: `common` (shared enums and model), `api` (REST service) and `worker`
(not implemented yet).

## Running it

You need Java 17 or newer, Maven 3.9 and Docker.

Start PostgreSQL. It listens on host port **5433**, so it doesn't clash with a
locally installed PostgreSQL on 5432:

```bash
docker compose up -d
```

Build and start the API on port 8080. Flyway applies the migrations on startup:

```bash
mvn package -DskipTests
```

```bash
java -jar api/target/api-0.1.0-SNAPSHOT.jar
```

To point it at a different database, set `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`.

## API

Errors come back as [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807) problem
responses.

| Method | Path | Result |
|---|---|---|
| `POST` | `/api/jobs` | `201` created, `200` repeat of an existing key, `400` invalid, `409` key reused with a different request |
| `GET` | `/api/jobs/{id}` | `200`, or `404` |
| `POST` | `/api/jobs/{id}/cancel` | `200` cancelled, `404` unknown, `409` not `PENDING` |

Submit a job:

```bash
curl -X POST http://localhost:8080/api/jobs -H "Content-Type: application/json" -H "Idempotency-Key: order-42" -d '{"type":"IO","payload":{"sleepMs":500}}'
```

- `type` is `CPU`, `IO` or `FLAKY`.
- `payload` must be a JSON object of at most 64 KB.
- `maxAttempts` is optional, from 1 to 10, default 5.
- `Idempotency-Key` is optional. Sending the same key and the same request again
  returns the original job instead of creating a new one.

## Tests

```bash
mvn verify
```

The integration tests start their own PostgreSQL 16 container through
Testcontainers, so Docker must be running. They don't touch the compose database.
They cover concurrent submissions with one idempotency key, validation, and
concurrent cancels, all through real HTTP requests against the running app.
