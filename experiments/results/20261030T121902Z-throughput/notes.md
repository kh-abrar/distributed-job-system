# Notes: connection pool A/B (E2)

Checks that sizing each worker's connection pool to worker.threads + 3 (7 with the
default 4 threads, down from HikariCP's default of 10) doesn't cost throughput.

| Run folder | Pool | Steady jobs/s per run | Median |
|---|---|---|---|
| 20261030T121601Z-throughput | 10 (forced with compose.pool-size.yml) | 3,218, 3,464, 3,510 | **3,464** |
| 20261030T121902Z-throughput (this one) | 7 (the worker's own sizing) | 3,678, 3,519, 2,933 | **3,519** |

The two ran back to back under the same conditions. The medians are within 2%,
well inside the spread between runs, and the mean job time is 2.3 ms in both.
**The smaller pool costs nothing.**

Why: every JobStore statement runs on its own and gives its connection back at
once, so a worker never needs more than one connection per handler thread plus one
each for the poll loop, the heartbeat and the reaper.

An earlier session with pool 7 (20261030T121115Z-throughput) gave a median of
2,581 jobs/s, 26% below the original baseline of 3,477. The A/B shows that was
variation between sessions on this machine, not the pool: its median is below every
one of the six runs here. It's kept as a reminder that comparisons need to run back
to back.

On the real stack, 4 workers now hold 28 connections instead of 40, so a default
PostgreSQL (100 connections) fits about 12 workers instead of about 9.
