# Clock monitor

PostgreSQL's clock_timestamp() minus the host's clock, sampled every 0.2 s
for 120 s (600 samples).

Offset at start: -0.478 s; at end: 0.018 s.

| Seconds into the run | Jump (s) | Offset after (s) |
|---|---|---|
| 11.3 | +1.3 | -0.0 |
