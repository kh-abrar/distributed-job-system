# Worker failures (E4, E5)

Worker settings: lease 30 s, heartbeat 10 s, reaper 5 s, poll 0.5 s.
Times from the host's clock. Environment: environment.txt.

## E4: worker killed with SIGKILL

Recovery is the time from the kill until another worker claimed the job
(attempt 2). Predicted for any kill: 20
to 35.5 s (lease minus heartbeat
interval, to lease plus reaper and poll intervals). Per trial, recovery minus
the lease left at the kill should be between 0 and 5.5 s.

| Trial | Kill after claim (s) | Lease left (s) | **Recovery (s)** | Recovery − lease left (s) | OK |
|---|---|---|---|---|---|
| 1 | 7.05 | 23.21 | **25.64** | 2.43 | yes |
| 2 | 1.01 | 27.87 | **31.04** | 3.17 | yes |
| 3 | 2.39 | 26.44 | **30.26** | 3.82 | yes |
| 4 | 4.79 | 23.93 | **27.87** | 3.94 | yes |
| 5 | 5.64 | 23.05 | **27.02** | 3.97 | yes |
| 6 | 0.31 | 28.50 | **32.22** | 3.72 | yes |
| 7 | 5.49 | 23.06 | **27.57** | 4.51 | yes |
| 8 | 9.84 | 26.58 | **27.87** | 1.29 | yes |
| 9 | 4.72 | 24.15 | **27.60** | 3.45 | yes |
| 10 | 8.40 | 28.66 | **33.81** | 5.15 | yes |

Recovery: min 25.64 s, median 27.87 s, max 33.81 s over 10 trials.

- Within the predicted 20-35.5 s: 10 of 10
- Recovery − lease left within 0-5.5 s (±0.5 s for measuring): 10 of 10
- History correct (dead worker LEASE_EXPIRED, other worker SUCCEEDED): 10 of 10

## E5: worker frozen with docker pause

The owner is frozen until another worker has taken the job over, then woken.
Its stale write must be rejected: attempt 1 stays LEASE_EXPIRED, attempt 2 is
SUCCEEDED by the other worker, and the woken worker logs that it lost the run.

| Trial | Freeze after claim (s) | Frozen for (s) | Stale write rejected | History | OK |
|---|---|---|---|---|---|
| 1 | 0.70 | 31.86 | yes | `1:LEASE_EXPIRED:2c49e3c4dc3c 2:SUCCEEDED:6409b12e635f` | yes |
| 2 | 5.19 | 28.53 | yes | `1:LEASE_EXPIRED:2c49e3c4dc3c 2:SUCCEEDED:6409b12e635f` | yes |
| 3 | 0.70 | 34.06 | yes | `1:LEASE_EXPIRED:6409b12e635f 2:SUCCEEDED:2c49e3c4dc3c` | yes |
| 4 | 4.58 | 29.28 | yes | `1:LEASE_EXPIRED:6409b12e635f 2:SUCCEEDED:2c49e3c4dc3c` | yes |
| 5 | 5.48 | 29.51 | yes | `1:LEASE_EXPIRED:2c49e3c4dc3c 2:SUCCEEDED:6409b12e635f` | yes |
| 6 | 4.33 | 30.63 | yes | `1:LEASE_EXPIRED:6409b12e635f 2:SUCCEEDED:2c49e3c4dc3c` | yes |
| 7 | 8.94 | 35.06 | yes | `1:LEASE_EXPIRED:6409b12e635f 2:SUCCEEDED:2c49e3c4dc3c` | yes |
| 8 | 1.69 | 31.66 | yes | `1:LEASE_EXPIRED:2c49e3c4dc3c 2:SUCCEEDED:6409b12e635f` | yes |
| 9 | 4.95 | 29.50 | yes | `1:LEASE_EXPIRED:6409b12e635f 2:SUCCEEDED:2c49e3c4dc3c` | yes |
| 10 | 0.85 | 33.54 | yes | `1:LEASE_EXPIRED:2c49e3c4dc3c 2:SUCCEEDED:6409b12e635f` | yes |

All checks passed: 10 of 10 trials.
