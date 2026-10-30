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
| 4 | 60000 | 3135.8, 1624.4, 2838.7 | **2838.7** | 2827.3 | 2.3 | 19 | 1.83 | 0 |

## E3: CPU jobs

| Workers | Jobs | Steady jobs/s per run | **Median steady jobs/s** | Median overall jobs/s | Mean job ms | Dropped rows | Jobs per claim | Not succeeded |
|---|---|---|---|---|---|---|---|---|
| 1 | 2400 | 52.1, 80.6, 87.9 | **80.6** | 77.4 | 41.5 | 12 | 1.01 | 0 |
| 2 | 4800 | 109.0, 121.6, 59.1 | **109.0** | 108.7 | 62.8 | 48 | 1.01 | 0 |
| 4 | 9600 | 124.8, 132.1, 142.8 | **132.1** | 130.6 | 103.9 | 103 | 1.05 | 0 |
| 8 | 19200 | 128.6, 141.2, 163.3 | **141.2** | 143.9 | 160.9 | 345 | 1.08 | 0 |

## E3: IO jobs (sleepMs 100)

| Workers | Jobs | Steady jobs/s per run | **Median steady jobs/s** | Median overall jobs/s | Mean job ms | Dropped rows | Jobs per claim | Not succeeded |
|---|---|---|---|---|---|---|---|---|
| 1 | 1200 | 37.6, 23.2, 37.4 | **37.4** | 36.2 | 101.7 | 16 | 1.59 | 0 |
| 2 | 2400 | 46.0, 72.0, 73.1 | **72.0** | 72.0 | 102.6 | 24 | 1.33 | 0 |
| 4 | 4800 | 145.4, 147.1, 146.1 | **146.1** | 146.0 | 102.6 | 47 | 1.27 | 0 |
| 8 | 9600 | 186.9, 295.4, 294.5 | **294.5** | 294.8 | 102.1 | 93 | 1.14 | 0 |
