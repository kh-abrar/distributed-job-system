# Notes on this throughput run (the valid E2 and E3 results)

Run after the test machine's clock fault was fixed (see
results/20261030T101215Z-clock/notes.md). All 27 runs finished every job. No run
has a stall: no sample stretch without progress. environment.txt records a clock
skew of 0 or 1 s for every run; the 1 s is rounding in how the skew is measured
(two separate commands), not a jump.

## E2: throughput with 4 workers

Spec section 9 guessed "four workers handling at least 500 lightweight (near
no-op) jobs per second". Measured median: **3,477 jobs/s** (runs: 3,593, 2,941,
3,477). The guess holds by about 7x.

Where the database time went in run 3 (statements.txt), over a 17.7 s run:
- complete: 60,000 calls, 0.24 ms each, 14.4 s in total
- claim: 32,112 calls, 0.37 ms each, 11.8 s in total; 1.85 jobs per claim

That is about 26 s of statement time in 17.7 s, so on average about 1.5 of the
12 CPUs were executing statements: PostgreSQL was busy but not saturated.
Unverified hypothesis for the limit: each worker has one poll thread, and a claim
takes at most as many jobs as the worker has free threads (4), so claim round
trips cap each worker at roughly 870 jobs/s here. More handler threads or a larger
batch would test it.

## E3: scaling

**IO jobs (sleepMs 100): near-linear.** 38.6, 76.8, 152.6 and 307.4 jobs/s for 1, 2,
4 and 8 workers: 95 to 97% of the ideal 40, 80, 160 and 320 (workers x 4 threads x
10 jobs/s). Runs within a setting agree to about 1%. Up to 8 workers, nothing else
became the bottleneck.

**CPU jobs (about 44 ms of hashing each): flat after 2 workers.** 86.6, 147.0, 149.6
and 156.0 jobs/s for 1, 2, 4 and 8 workers, while the mean job time grows from 44
to 52, 97 and 173 ms as threads queue for CPUs. 150 jobs/s x 44 ms is about 6.6
CPU-seconds per second, close to the host's 6 physical cores (Ryzen 5 5600G: 6
cores, 12 threads; Docker sees 12). SHA-256 gains little from the second thread
per core, so the plateau is the machine's CPU, not the job system: with 2 workers
(8 handler threads) the hardware is already saturated.

Spec section 9's guess ("adding workers improves throughput ... until some other
bottleneck takes over") holds: for IO jobs no other bottleneck appeared up to 8
workers; for CPU jobs the bottleneck is the hardware.

## Minor, unexplained

E2's runs each had 6 to 11 (26 in total, of 180,000) job durations outside 0 to 60
s, although the clock no longer jumps. They don't affect any rate (rates use the
host clock), and only E2's 2 ms jobs show them. Not investigated; the rows were
cleared by the next run's reset.

## Superseded runs

- results/20261030T060120Z-throughput: invalid; rates from a jumping clock.
- results/20261030T073020Z-throughput: valid timing, but 8 runs contain 15 s stalls
  caused by the clock fault. Kept as evidence of how the system behaves when
  PostgreSQL's clock steps backwards.
