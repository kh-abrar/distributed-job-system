# Notes on E6: duplicate execution

Spec section 7, row 3: a worker "crashes after the handler finishes but before
the fenced SUCCEEDED update commits", and "another worker runs the job again".
ADR-003 says such duplicates "have to be tested on purpose". This run did that.

Setup: 400 IO jobs (200 ms each), 2 workers with the spec's settings (30 s lease).
With fault injection on (worker.fault.crash-after-handler-probability 0.04),
after each successful handler a worker exited with Runtime.halt with probability
4%, before recording the result, and Docker restarted it. environment.txt records
the worker image, built from the code that contains FaultInjector.

## Results

- **Every job was done: 400 of 400 SUCCEEDED, none DEAD** (G2, at-least-once),
  despite 16 crashes. All jobs finished 86 s after the workers started; without
  crashes, 400 jobs of 200 ms on 8 threads take about 10 s, so most of that time
  was waiting for leases to run out.
- **16 crashes, 16 duplicate completions.** Every crash cut off a handler that had
  finished; in all 16 cases the job's handler later finished again on another
  attempt and succeeded. The logs (16 FAULT INJECTION lines) and Docker (8 + 8
  restarts) agree on the count.
- **Every duplicate is visible in the history.** All 16 crashed runs are recorded
  as LEASE_EXPIRED, followed by a later SUCCEEDED attempt.
- **26 more runs were cut short**: they were mid-handler on a worker when it
  crashed. Those jobs also ran again, but their handlers had not finished the
  first time, so whether they count as duplicates depends on what the handler did
  before it was stopped.
- **442 runs, 442 distinct (job_id, generation, attempt) values.** Each run has its
  own identity, so a handler that records what it did under (job_id, generation,
  attempt) can always tell a repeat from the original.

## What it shows

The system kept its promise: no job lost, and duplicates happen exactly where the
spec says they will, no more. The crash took the result with it, and fencing
didn't need to step in: the crashed worker never wrote again, and the lease
running out is what triggered the rerun.

What the system can't do is make the side effects of a finished-but-unrecorded
run go away. That is the handler's job (ADR-003). These handlers have no external
side effects, so the duplicates did no harm; a handler that, say, sends an email
would have sent 16 of them twice unless it checked (job_id, generation, attempt)
against its own log first.

## Dry runs

A dry run with 60 jobs at 10% gave the same picture (60 of 60 succeeded, 12
crashes, 12 duplicate completions). An earlier dry run measured nothing: the
experiment stack's worker image predated FaultInjector, because the harness didn't
rebuild it. The harness now rebuilds both images on every run, records which
image ran, and refuses to measure unless the workers log that fault injection is on.
