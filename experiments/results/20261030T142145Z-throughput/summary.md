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
| 4 | 60000 | 3356.5, 2970.8, 2457.4 | **2970.8** | 2883.8 | 2.7 | 15 | 1.89 | 0 |
