# Phase 5: load and failure experiments

The spec (Section 9) made four guesses about performance and recovery, and
Section 7 described how the system should behave when workers fail. This document
reports what was measured, how, and what it showed, including what went wrong
along the way.

## Summary

| Spec guess or claim | Measured | Verdict |
|---|---|---|
| Submission p95 under 50 ms at about 200 requests/s (§9) | **p95 3.61 ms** | Holds, about 14x better |
| Four workers handle at least 500 near no-op jobs/s (§9) | **3,477 jobs/s** | Holds, about 7x better |
| A killed worker's job recovers in about 40 s (§9); within roughly lease + reaper interval (G3) | **median 27.9 s**, all 10 trials within the predicted 20-35.5 s | Holds |
| More workers help until another bottleneck takes over (§9) | IO jobs: near-linear up to 8 workers. CPU jobs: flat from 2 workers, limited by the machine's 6 cores | Holds |
| A frozen worker that wakes up can't overwrite newer state (§7, G5) | stale write rejected in **10 of 10** trials | Holds |
| A crash between finishing and recording runs the job again (§7 row 3, ADR-003) | **16 of 16** such crashes produced a duplicate run; **400 of 400** jobs still done | Holds, as designed |

Two findings the spec didn't anticipate:

- **The single database clock must not jump.** Assumption 3 makes PostgreSQL's
  `now()` the only clock, which removes any dependence on worker clocks. But when
  that clock stepped backwards on the test machine, claims stalled for 15 s at a
  time. See [The clock fault](#the-clock-fault).
- **Connections, not polling, set the first scaling limit.** With default
  settings each worker holds up to 10 database connections, so a default
  PostgreSQL (100 connections) runs out at about 9 workers. See
  [Other findings](#other-findings).

## Setup

- **Machine:** one laptop. AMD Ryzen 5 5600G (6 cores, 12 threads), 16 GB RAM,
  Windows 11 with Docker Desktop (WSL2 VM: 12 CPUs, 7.7 GB). The load generator,
  api, PostgreSQL 16 and workers all shared it. Absolute numbers hold for this
  machine; the comparisons (0 vs 4 workers, 1 vs 8, IO vs CPU) are what carry over.
- **Worker settings:** the spec's (Section 8): lease 30 s, heartbeat 10 s, reaper
  5 s, poll 0.5 s, claim batch 10, 4 handler threads per worker.
- **Harness:** [`experiments/`](../experiments/). Each experiment is one script
  that starts its own Compose project (`jobs-exp`, own volume and ports), resets
  the database, runs, and saves raw output plus a `summary.md` and `notes.md`
  under `experiments/results/`. Every run records its environment: code version,
  container images, Docker resources and clock skew.
- **Timing:** the plan was to take every time from PostgreSQL's single clock. The
  clock fault made that unreliable, so the final method times everything on the
  host: k6's own timer for latency, a sampler that counts finished jobs every
  0.2 s against the host clock for throughput, and host timestamps around each
  kill or freeze.

## E1: submission latency

k6 sent `POST /api/jobs` at a constant arrival rate of 200 requests/s for 2
minutes after a 30 s warm-up, from inside the Docker network. Constant arrival
rate matters: a send-and-wait loop slows down with the server and hides latency.
Three runs with no workers, three with 4 workers draining the queue at the same
time.

| Workers running | Median p50 | Median p95 | Median p99 | Errors |
|---|---|---|---|---|
| 0 | 2.08 ms | **3.61 ms** | 4.75 ms | 1 of 72,003 requests |
| 4 | 2.15 ms | **3.61 ms** | 4.60 ms | none |

Workers draining the queue made no difference to submission latency, and with 4
workers the queue was empty at the end of every run. k6's reported maximum
(about 319,550 ms in every run) is an artifact of the clock fault and is excluded;
the slowest iteration, which bounds the slowest request, was 82-440 ms.
The one failed request had no matching api error and is unexplained.

Results: [`20261030T052919Z-e1-latency`](../experiments/results/20261030T052919Z-e1-latency/)

## E2 and E3: throughput and scaling

Jobs were inserted straight into the table (so the api wasn't measured) behind a
"gate" (`run_at` an hour ahead), every worker was started and ready, then the gate
opened with one `UPDATE`. Three runs per setting.

**E2: 4 workers, 60,000 near no-op jobs (IO, sleepMs 0): median 3,477 jobs/s**
(runs 3,593, 2,941, 3,477). PostgreSQL was busy but not saturated: about 26 s of
statement time in a 17.7 s run, so about 1.5 of 12 CPUs. Unverified hypothesis
for the limit: each worker has one poll thread, and a claim takes at most as many
jobs as it has free threads (4), so claim round trips cap each worker at roughly
870 jobs/s.

**E3: scaling, jobs/s by number of workers**

| Workers | IO (100 ms jobs) | Ideal | CPU (about 44 ms of hashing) | Mean CPU job time |
|---|---|---|---|---|
| 1 | 38.6 | 40 | 86.6 | 44 ms |
| 2 | 76.8 | 80 | 147.0 | 52 ms |
| 4 | 152.6 | 160 | 149.6 | 97 ms |
| 8 | **307.4** | 320 | 156.0 | 173 ms |

- **IO scales near-linearly**, at 95-97% of ideal, with runs agreeing to about 1%.
  Nothing else became a bottleneck up to 8 workers.
- **CPU flattens at about 150 jobs/s from 2 workers.** 150 jobs/s x 44 ms is about
  6.6 CPU-seconds per second, the host's 6 physical cores. More workers only make
  each job wait longer for a core. The limit is the hardware, not the job system.

Results: [`20261030T101343Z-throughput`](../experiments/results/20261030T101343Z-throughput/)

## E4: a worker killed with SIGKILL

Each trial: 2 workers, one 15 s job, the owner killed (`docker kill`) a random 0-10 s
after claiming it, so the kill lands anywhere between heartbeats.

The prediction can be made exact. The last heartbeat is up to 10 s before the kill,
so the remaining lease is 20-30 s; then the reaper notices within 5 s and a worker
claims within 0.5 s. **Recovery should take 20-35.5 s, and exceed the lease left
at the kill by 0-5.5 s.**

- Recovery: **min 25.6 s, median 27.9 s, max 33.8 s**. 10 of 10 within 20-35.5 s.
- Recovery minus lease left: 1.3-5.2 s. 10 of 10 within 0-5.5 s: once a lease runs
  out, the only delay is the reaper's interval plus a poll.
- History correct in 10 of 10: attempt 1 `LEASE_EXPIRED` by the killed worker,
  attempt 2 `SUCCEEDED` by the other.

The spec's guess of "about 40 s" was a little pessimistic; G3's bound is the
accurate description.

Results: [`20261030T112742Z-failures`](../experiments/results/20261030T112742Z-failures/)

## E5: a frozen worker wakes up

Each trial froze the owner with `docker pause` (like SIGSTOP) until the other
worker had taken the job over (28.5-35.1 s), then woke it while the other worker
still owned the job.

**10 of 10 trials passed every check:** attempt 1 stayed `LEASE_EXPIRED`, attempt 2
ended `SUCCEEDED` by the other worker, and the woken worker logged that it had
lost the run.

The logs show how. Every time, the woken worker's heartbeat found the lease gone
and interrupted the run, but the handler's sleep had already run out during the
freeze, so it finished anyway and tried to complete. **The fenced write was
rejected every time.** Fencing, not the heartbeat's interrupt, is what kept the
stale result out; the interrupt only saves work when a handler is still mid-run.

Results: same folder as E4.

## E6: duplicate execution

ADR-003 says duplicates "have to be tested on purpose". A crash that lands after a
handler finishes but before its result is recorded is a window of milliseconds,
so the worker got a fault-injection switch
([`FaultInjector`](../worker/src/main/java/com/system/worker/execution/FaultInjector.java),
off by default): with a set probability it exits with `Runtime.halt` at exactly
that point. Docker restarted crashed workers.

400 IO jobs, 2 workers, 4% chance of a crash after each successful handler:

| Measure | Value |
|---|---|
| Jobs done | **400 of 400 SUCCEEDED**, none DEAD |
| Injected crashes | 16 (16 log lines, 16 restarts) |
| Duplicate completions: finished, result lost, finished again later | **16 of 16** |
| Other runs cut short mid-handler by a crash | 26 |
| Runs with a unique (job_id, generation, attempt) | 442 of 442 |

No job was lost (G2), duplicates happened exactly where Section 7 says they will,
and each was visible in `job_attempts`. The system can't undo the side effects of
a run that finished but wasn't recorded; that is the handler's job, and every run
has a unique (job_id, generation, attempt) for it to check against.

Results: [`20261030T115907Z-duplicates`](../experiments/results/20261030T115907Z-duplicates/)

## The clock fault

The first throughput runs gave impossible numbers: negative job durations, and one
CPU run 7x slower than its neighbours. A clock monitor
([`clock-monitor.sh`](../experiments/clock-monitor.sh)) compared PostgreSQL's clock
with the host's every 0.2 s:

- PostgreSQL's clock read 319.5 s ahead of Windows.
- About every 30.5 s it stepped back to Windows' time, and something put it
  319.5 s ahead again, usually within 0.2 s, but about once every 5 minutes only
  after 15.4 s.

Checking against web servers' `Date` headers showed that **Windows was the wrong
clock**: a leftover policy setting (`NtpServer` set to an empty name) had stopped
it from ever syncing, and it had drifted about 320 s slow. Hyper-V kept pushing
that wrong time into Docker's VM; the VM's own time sync kept putting the real
time back. Removing the policy and syncing Windows fixed it: afterwards no jumps,
and PostgreSQL within 0.05 s of the host.

**What it did to the job system.** Throughput runs made during the fault stalled
for 15.4 s at a time, about once every 5 minutes. The mechanism: a pending job is
claimable when `run_at <= now()`. While the clock read 319 s early, every job's
`run_at`, set by the correct clock, was 319 s "in the future", so no worker could
claim anything until the clock jumped forward again.

**What it shows about the design.** Assumption 3 ("lease expiry is checked with
PostgreSQL's `now()`, not worker clocks") is a good decision: it removes any
dependence on worker clocks agreeing. The experiment exposes the remaining
assumption, that **PostgreSQL's own clock never jumps**:

- A backward jump makes pending jobs unclaimable until it recovers (observed).
- A forward jump would make running jobs' leases look expired, so the reaper would
  recover jobs that are still running and they would run twice (predicted, not
  observed). At-least-once execution allows that, but it would be avoidable.

A database server that syncs its clock by slewing (adjusting its rate) rather than
stepping avoids both. It belongs in the deployment notes, not the code.

Evidence, kept with their own notes:
[the invalid run](../experiments/results/20261030T060120Z-throughput/),
[the run with stalls](../experiments/results/20261030T073020Z-throughput/),
[the clock monitor before](../experiments/results/20261030T082956Z-clock/) and
[after](../experiments/results/20261030T101215Z-clock/) the fix.

## Other findings

- **Database connections.** Each worker's pool holds up to 10 connections
  (HikariCP's default) and the api's another 10, so 8 workers plus the api need
  90 of PostgreSQL's default 100. A throughput run failed with "too many clients"
  when the harness leaked a few extra sessions. The experiments raised
  `max_connections` to 200; a real deployment would size worker pools to what a
  worker uses (its handler threads plus poll, heartbeat and reaper, so about 7)
  before adding workers.
- **History size (Open Question 3).** `job_attempts` costs about 200-230 bytes per
  run including indexes. A million runs is about 200 MB, so retention is not
  urgent at this scale.
- **Polling cost (Open Question 5).** At 307 jobs/s, PostgreSQL spent about 10% of
  one CPU on worker statements; at 3,477 jobs/s, about 1.6 CPUs. The average claim
  took twice as long at the higher rate (0.18 ms to 0.39 ms) as workers contended
  for rows. Polling was not the bottleneck at any rate measured here. The cost of
  idle polling with an empty queue was not measured.

## Problems with the harness, and how they were caught

Recorded so the results can be trusted for the right reasons:

- Timing from PostgreSQL's clock gave impossible numbers (the clock fault). Fixed
  by timing on the host.
- Each throughput sampler leaked a database session; 17 had accumulated when a
  run ran out of connections. Fixed by naming the session and ending it from the
  database side.
- The harness rebuilt the api image but not the worker image, so an E6 dry run ran
  code without fault injection and measured nothing. E1-E5 were unaffected (the
  worker code hadn't changed since that image was built). The harness now rebuilds
  both, records which images ran, and E6 refuses to measure unless the workers
  confirm fault injection is on.
- Timing around `docker compose exec` added about 3 s per query on this machine,
  which skewed the first E4 lease measurement. Queries now use `docker exec`.

## Limits of these results

- One machine, with the load generator competing for CPU. Results on separate
  hosts, with real network latency between workers and the database, would differ.
- Three runs per throughput setting and ten trials per failure experiment: enough
  to show the shape and check the bounds, not to report tight confidence intervals.
- E2's 3,477 jobs/s is for jobs that do nothing. Real handlers do work, so
  throughput would be set by them, as E3 shows.
- The forward clock jump's effect is reasoned, not measured.

## Reproducing

From Git Bash (or any POSIX shell) with Docker running:

```bash
bash experiments/run-e1-latency.sh
bash experiments/run-e2-e3-throughput.sh
bash experiments/run-e4-e5-failures.sh
bash experiments/run-e6-duplicates.sh
bash experiments/clock-monitor.sh 120
bash experiments/stop.sh
```

Each prints its summary and writes a new folder under `experiments/results/`.
Before trusting any timing, check `clock_skew_seconds_db_minus_host` in its
`environment.txt` (it should be close to 0) or run the clock monitor.
