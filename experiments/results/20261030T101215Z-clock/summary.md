# Clock monitor

PostgreSQL's clock_timestamp() minus the host's clock, sampled every 0.2 s
for 60 s (300 samples).

Offset at start: -0.054 s; at end: -0.041 s.

No jumps larger than 1 s.
