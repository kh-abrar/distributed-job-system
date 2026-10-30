# E1: submission latency

200 requests/s for 2m after a 30s warm-up. Latencies in milliseconds, measured by k6
inside the Docker network. Environment: see any run's environment.txt.

"Slowest iteration" bounds the slowest request from above. k6's own maximum request
time is not shown: it can absorb a clock step in Docker's VM (see notes.md).

## 0 workers running

| Run | Requests | p50 | p95 | p99 | Slowest iteration | Error rate | Dropped | Open jobs at end |
|---|---|---|---|---|---|---|---|---|
| 1 | 24001 | 2.18 | 3.70 | 4.75 | 439.81 | 0.000041664930627890504 | 17 | 29984 |
| 2 | 24001 | 2.08 | 3.61 | 4.54 | 106.34 | 0 | 0 | 30002 |
| 3 | 24001 | 1.99 | 3.42 | 5.04 | 82.20 | 0 | 0 | 30002 |
| **Median** | | **2.08** | **3.61** | **4.75** | | | | |

## 4 workers running

| Run | Requests | p50 | p95 | p99 | Slowest iteration | Error rate | Dropped | Open jobs at end |
|---|---|---|---|---|---|---|---|---|
| 1 | 24001 | 2.15 | 3.61 | 4.60 | 81.53 | 0 | 0 | 0 |
| 2 | 24001 | 2.22 | 3.73 | 5.08 | 126.18 | 0 | 0 | 0 |
| 3 | 24000 | 1.74 | 3.14 | 4.28 | 92.59 | 0 | 0 | 0 |
| **Median** | | **2.15** | **3.61** | **4.60** | | | | |
