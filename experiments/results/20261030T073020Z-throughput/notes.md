# Notes on this throughput run

All 27 runs finished every job. Timing is from the host's clock (a sampler counted
finished jobs every 0.2 s), so the rates here are not affected by the clock
problem that invalidated results/20261030T060120Z-throughput.

## 8 runs contain a stall caused by Docker's VM clock

In 8 runs, the finished-job count stopped moving for about 15.4 s (about 77
samples) while the sampler kept reporting on schedule; one run (e3-cpu,
2 workers, run 3) has two such stalls. Affected runs:

| Setting | Run | Flat samples |
|---|---|---|
| e2-noop, 4 workers | 2 | 77 |
| e3-io, 1 worker | 2 | 77 |
| e3-io, 2 workers | 1 | 77 |
| e3-io, 8 workers | 1 | 76 |
| e3-cpu, 1 worker | 1 | 77 |
| e3-cpu, 2 workers | 3 | 154 |
| e3-cpu, 8 workers | 1 | 79 |
| e3-cpu, 8 workers | 2 | 79 |

The cause was confirmed afterwards with experiments/clock-monitor.sh (results in
results/20261030T082956Z-clock). PostgreSQL's clock in Docker Desktop's WSL2 VM
runs 319.5 s ahead of the host. About every 30.5 s something steps it back to the
host's time, and something else puts it 319.5 s ahead again, usually within
0.2 s but twice in 10 minutes only after 15.4 s. During those 15.4 s every
pending job's run_at, set by the "ahead" clock, is 319 s in the future, so no
worker can claim anything. 15.4 s is exactly the stall length.

The short 0.2 s corrections also explain the "dropped rows" (job durations that
are negative or longer than 60 s) in every setting.

### Root cause (found afterwards)

The reading above names the wrong clock as faulty. Checked against web servers'
Date headers, Docker's VM had the real time and **Windows was about 320 s slow**:
its time service used "Local CMOS Clock" as its source, so it never synced with
an internet time server. So it is the host's wrong time that Hyper-V pushes into
the VM about every 30 s (the backward step), and the VM's own time sync that puts
the real time back. The observations, and what they mean for the job system, are
unchanged; only which side is wrong.

## How to read the numbers

The stalls measure the environment's clock fault, not the job system. A stalled
run's steady rate is too low; summary.md's medians include them, so for settings
with a stalled run, the stall-free runs are the better guide. Where two of three
runs stalled (e3-cpu, 8 workers), the median itself comes from a stalled run.

## What it shows about the design

Spec Assumption 3 makes PostgreSQL's now() the only clock, so worker clocks never
matter. The stalls show the remaining dependency: that one clock must not jump.
A backward jump makes pending jobs unclaimable until it recovers (observed here).
A forward jump would make running jobs' leases look expired, so the reaper would
recover them while they still run and they would run twice (predicted, not
observed: the stalled runs' history was cleared by the next reset).
