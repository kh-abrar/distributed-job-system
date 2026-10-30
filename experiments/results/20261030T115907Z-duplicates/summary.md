# E6: duplicate execution

400 IO jobs (sleepMs 200), 2 workers. After each successful handler a worker
exits with probability 0.04, before recording the result; Docker restarts it.

| Measure | Value |
|---|---|
| jobs | 400 |
| jobs_succeeded | 400 |
| jobs_dead | 0 |
| jobs_other | 0 |
| runs | 442 |
| runs_distinct_by_job_generation_attempt | 442 |
| jobs_run_more_than_once | 38 |
| lease_expired_runs | 42 |
| crashed_runs | 16 |
| crashed_runs_recorded_as_lease_expired | 16 |
| duplicate_completions | 16 |
| runs_cut_short_by_a_crash | 26 |
| max_attempts_used_by_one_job | 3 |

- **crashed_runs**: handlers that finished, then lost their result to an injected crash
- **duplicate_completions**: of those, jobs whose handler later finished again and succeeded
- **runs_cut_short_by_a_crash**: other runs on a crashing worker, stopped mid-handler

Restarts per worker container:

- jobs-exp-worker-1 restarts: 8
- jobs-exp-worker-2 restarts: 8
