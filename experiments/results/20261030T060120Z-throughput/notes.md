# Notes on this throughput run: not valid results

Kept as evidence of a measurement problem, not as results. Don't quote these numbers.

## Docker's VM clock stepped backwards during the runs

Throughput and job durations were computed from PostgreSQL timestamps, on the
assumption that one database clock is consistent. It wasn't: Docker Desktop's
WSL2 VM ran about 319 s ahead of the host, and every so often its clock stepped
back by about that much, then jumped ahead again.

Evidence, from the database during the run with 8 CPU workers:
- 20 of 5,269 finished jobs had finished_at earlier than started_at, by as much
  as 318.6 s.
- Most runs report a negative mean job duration (for example -961 ms for 100 ms
  IO jobs), which is impossible.
- One CPU run reports 21 jobs/s where neighbouring runs report 135 to 152.

The steady-state rate ignores the earliest and latest 10% of finishes, so runs
with only a few affected rows are probably close to right (E2: 2,395 to 3,225
jobs/s; E3 IO: about 37, 74, 145 and 290 jobs/s for 1, 2, 4 and 8 workers). But
which runs were affected can't be told reliably from the data, so none of them
is used.

Correction, found later: Docker's VM had the real time; Windows was about 320 s
slow (its time service wasn't syncing). The backward steps are Hyper-V pushing the
host's wrong time into the VM. See results/20261030T073020Z-throughput/notes.md.

## The run did not finish

During the third run with 8 CPU workers, Docker Desktop stopped responding
("Bad Gateway" from its API, then the engine pipe disappeared). No summary.md
was written for this directory.

## What changes for the rerun

Timing moves to the host's clock, which is steady: a sampler counts finished
jobs once a second and the rate is the slope of that count. Job durations are
reported only from rows that are physically plausible, with the number of
discarded rows stated.
