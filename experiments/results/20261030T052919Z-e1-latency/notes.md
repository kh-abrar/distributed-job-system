# Notes on this E1 run

## Verdict

Spec section 9 guessed a submission p95 under 50 ms at about 200 requests/s.
Measured median p95: **3.61 ms** with no workers and **3.61 ms** with 4 workers
draining the queue at the same time. The guess holds with a wide margin, and
running workers made no measurable difference to submission latency. With 4
workers, the queue was empty at the end of every run: they kept up with 200 jobs/s.

## Caveats

- Everything ran on one machine (see environment.txt): k6, the api, PostgreSQL and
  the workers shared its CPU. k6 called the api inside the Docker network, so the
  numbers leave out the cost of Docker Desktop's port forwarding.
- The jobs were IO jobs with sleepMs 0, so the workers did almost no work of their
  own; they mostly added database traffic.
- environment.txt says git_dirty: yes. The uncommitted changes at the time were
  tests, documentation and this harness; the api and worker code was the same as
  commit 2dd31e3.

## Anomalies

**k6's maximum request time is wrong in every run (about 319,550 ms).** It is not
real latency:

- A whole run lasts 150 s, so no request can take 319 s.
- In each run, the slowest k6 iteration took 82 to 440 ms, and an iteration
  contains its request.
- 319 s matches the measured clock skew between Docker's VM and the host
  (clock_skew_seconds_db_minus_host: 319). Once per run, a clock correction in the
  VM landed inside one request's timing. (Found later: the host was the wrong
  clock, about 320 s slow, and Hyper-V periodically pushed that wrong time into the
  VM; see results/20261030T073020Z-throughput/notes.md.)

One sample in 24,000 does not change p50, p95 or p99, which is why they are
reported. summary.md shows the slowest iteration instead of k6's maximum.

**Run 1 with no workers: 1 failed request and 17 dropped iterations.** k6 grew
from 100 to 117 virtual users during that run, and the slowest iteration took
440 ms, so there was a short stall. The api logged no error for it, so the failure
most likely happened at the connection level on k6's side. The data saved here
can't tell which; the other five runs had no failures and no dropped iterations.
