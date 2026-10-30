# Distributed Job Processing System

A small job queue built to study the hard parts of distributed work: leases,
fencing, retries, idempotent submission and recovery from crashed workers.
Clients submit jobs over a REST API, PostgreSQL is the source of truth, and a
pool of workers claims and runs them with at-least-once execution.

This is a learning and portfolio project, not a production platform. The design
comes first: see the [requirements and design spec](docs/00-requirements.md) and
the [architecture decisions](docs/decisions/). Where the build settles one of the
spec's open questions or differs from it, the ADRs say so (see
[ADR-006](docs/decisions/ADR-006.md)).

## Status

| Area | Spec | Status |
|---|---|---|
| Submit a job, with idempotency key | FR-1 to FR-6, G1, G4 | Done, tested |
| Fetch a job | FR-7, FR-8 | Done, tested |
| List jobs with cursor pagination | FR-9, FR-10 | Done, tested |
| Cancel a pending job | FR-11 to FR-13 | Done, tested |
| Manually requeue a dead job | FR-27 | Done, tested |
| Worker: claim, lease, heartbeat, fencing, retry, reaper | FR-14 to FR-26, G2, G3, G5, G6 | Done, tested |
| Load and failure experiments | Phase 5 | Not started |

## How it works

- **Claiming.** Workers poll PostgreSQL and claim jobs with `FOR UPDATE SKIP LOCKED`,
  so competing workers never take the same job. A claim takes only as many jobs
  as the worker has free threads.
- **Leases and fencing.** A claim gives the worker a 30 s lease, renewed by a
  heartbeat every 10 s. Every later write (heartbeat, complete, fail) only
  succeeds if the worker still owns that run: same `locked_by`, `attempts` and
  `generation` as when it claimed it. A worker that lost its lease can't
  overwrite anything ([ADR-005](docs/decisions/ADR-005.md)).
- **Retries.** A failed attempt goes back to `PENDING` after an exponential
  backoff with jitter (2 s doubling up to 60 s). On the last attempt the job is
  `DEAD`.
- **Recovery.** Every worker runs a reaper every 5 s. A job whose lease ran out
  (its worker crashed or froze) goes back to `PENDING` and another worker runs it.
- **History.** Every attempt is a row in `job_attempts`, including lease
  expiries and manual requeues.

Execution is at-least-once: a job can run more than once, for example when a
worker crashes after finishing a job but before recording it. Handlers receive
the job id, generation and attempt so they can detect repeats.

## Running it

You need Docker. This builds both services from source and starts PostgreSQL,
the API on port 8080 and two workers:

```bash
docker compose up -d --build --scale worker=2
```

The API applies the database migrations on startup. PostgreSQL is also
published on host port **5433**, so it doesn't clash with a locally installed
PostgreSQL on 5432.

To see a crash being recovered, submit a job that runs for a while:

```bash
curl -X POST http://localhost:8080/api/jobs -H "Content-Type: application/json" -d '{"type":"IO","payload":{"sleepMs":5000}}'
```

Find the worker that claimed it. A worker id starts with its container id:

```bash
docker compose exec postgres psql -U jobs -d jobs -c "SELECT id, locked_by FROM jobs WHERE status = 'RUNNING'"
```

Kill that container with SIGKILL, using the part of `locked_by` before the first `-`:

```bash
docker kill <container-id>
```

The other worker recovers the job once its lease has run out: within about 35 s
(lease plus reaper interval). The job's history shows a `LEASE_EXPIRED` attempt
by the dead worker, then a `SUCCEEDED` attempt by the other one. `docker compose stop`
instead sends SIGTERM, and a worker then finishes its running jobs (up to 30 s)
before exiting.

### Without Docker for the services

You need Java 17 or newer and Maven 3.9. Start only the database, then each
service from its jar:

```bash
docker compose up -d postgres
```

```bash
mvn package -DskipTests
```

```bash
java -jar api/target/api-0.1.0-SNAPSHOT.jar
```

```bash
java -jar worker/target/worker-0.1.0-SNAPSHOT.jar
```

To point a service at a different database, set `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`. Worker settings
(threads, lease, intervals, backoff) are in
[worker/src/main/resources/application.yml](worker/src/main/resources/application.yml).

## API

Errors come back as [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807) problem
responses.

| Method | Path | Result |
|---|---|---|
| `POST` | `/api/jobs` | `201` created, `200` repeat of an existing key, `400` invalid, `409` key reused with a different request |
| `GET` | `/api/jobs/{id}` | `200`, or `404` |
| `GET` | `/api/jobs?status=&cursor=&limit=` | `200` with `items` and `nextCursor`, `400` invalid parameter |
| `POST` | `/api/jobs/{id}/cancel` | `200` cancelled, `404` unknown, `409` not `PENDING` |
| `POST` | `/api/jobs/{id}/requeue` | `200` requeued with a fresh attempt budget in a new `generation`, `404` unknown, `409` not `DEAD` |

Submit a job:

```bash
curl -X POST http://localhost:8080/api/jobs -H "Content-Type: application/json" -H "Idempotency-Key: order-42" -d '{"type":"IO","payload":{"sleepMs":500}}'
```

- `payload` must be a JSON object of at most 64 KB.
- `maxAttempts` is optional, from 1 to 10, default 5.
- `Idempotency-Key` is optional. Sending the same key and the same request again
  returns the original job instead of creating a new one.

List jobs: pass the previous page's `nextCursor` as `cursor` to get the next
page, until `nextCursor` is `null`. `status` and `cursor` are optional, and
`limit` is 1 to 200 (default 50). Jobs come back in id order, which is stable
across pages but not chronological, because ids are random UUIDs.

```bash
curl "http://localhost:8080/api/jobs?status=PENDING&limit=20"
```

### Job types

| Type | Payload | Result |
|---|---|---|
| `CPU` | `iterations` (1 to 100,000,000, default 100,000), `seed` (default 0) | `{"iterations", "digest"}`: repeated SHA-256, the same for the same input |
| `IO` | `sleepMs` (0 to 600,000, default 1,000) | `{"sleptMs"}` |
| `FLAKY` | `succeedOnAttempt` (fails every attempt before it) and/or `failProbability` (0 to 1). Neither: fails half the time | `{"attempt"}` |

A payload parameter of the wrong type or out of range fails the attempt with an
`Invalid payload` error, like any other failure.

## Tests

```bash
mvn verify
```

The integration tests start their own PostgreSQL 16 container through
Testcontainers, so Docker must be running. They don't touch the compose
database. Among other things they cover:

- concurrent submissions with one idempotency key creating one job
- pagination while jobs change status
- many workers claiming jobs, each job exactly once
- a worker that lost its lease being unable to write anything, including across a manual requeue
- retries stopping at `maxAttempts`
- the reaper recovering a dead worker's job
- the whole worker running against the database: retries, heartbeats keeping long jobs alive, and graceful shutdown
