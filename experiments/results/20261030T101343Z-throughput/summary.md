# Throughput (E2, E3)

Jobs per second, timed with the host's clock: finished jobs were counted
every 0.2 s. "Steady" is the rate between 10% and 90% of the jobs finishing,
leaving out ramp-up and the tail; "overall" runs from opening the gate to the
last job. Each worker has the default 4 handler threads.

"Mean job ms" comes from database timestamps, keeping only rows between 0 and
60 s; "Dropped rows" counts the others, which show Docker's VM clock jumping.
"Jobs per claim" divides claimed jobs by claim statements, including polls that
found nothing; below 1 means more polls than jobs.
Environment: see any run's environment.txt.

## E2: near no-op jobs (IO, sleepMs 0)

| Workers | Jobs | Steady jobs/s per run | **Median steady jobs/s** | Median overall jobs/s | Mean job ms | Dropped rows | Jobs per claim | Not succeeded |
|---|---|---|---|---|---|---|---|---|
| 4 | 60000 | 3592.5, 2940.5, 3476.6 | **3476.6** | 3390.4 | 2.3 | 26 | 1.85 | 0 |

## E3: CPU jobs

| Workers | Jobs | Steady jobs/s per run | **Median steady jobs/s** | Median overall jobs/s | Mean job ms | Dropped rows | Jobs per claim | Not succeeded |
|---|---|---|---|---|---|---|---|---|
| 1 | 2400 | 91.5, 83.0, 86.6 | **86.6** | 85.0 | 44.4 | 0 | 1.00 | 0 |
| 2 | 4800 | 149.1, 144.6, 147.0 | **147.0** | 145.0 | 52.5 | 0 | 1.00 | 0 |
| 4 | 9600 | 164.9, 146.6, 149.6 | **149.6** | 148.9 | 96.9 | 0 | 1.03 | 0 |
| 8 | 19200 | 170.2, 156.0, 140.9 | **156.0** | 156.8 | 172.7 | 0 | 1.08 | 0 |

## E3: IO jobs (sleepMs 100)

| Workers | Jobs | Steady jobs/s per run | **Median steady jobs/s** | Median overall jobs/s | Mean job ms | Dropped rows | Jobs per claim | Not succeeded |
|---|---|---|---|---|---|---|---|---|
| 1 | 1200 | 38.4, 38.6, 38.6 | **38.6** | 38.2 | 101.7 | 0 | 1.74 | 0 |
| 2 | 2400 | 76.9, 76.7, 76.8 | **76.8** | 76.4 | 101.9 | 0 | 1.64 | 0 |
| 4 | 4800 | 154.3, 152.4, 152.6 | **152.6** | 152.7 | 102.5 | 0 | 1.43 | 0 |
| 8 | 9600 | 308.6, 307.4, 307.3 | **307.4** | 305.1 | 102.1 | 0 | 1.16 | 0 |
