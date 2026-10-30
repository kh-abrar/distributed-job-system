# Notes on the failure experiments (E4, E5)

Run with the test machine's clock fixed (environment.txt: skew -0.15 s, which is
mostly the time the skew query itself takes). Settings: 2 workers, lease 30 s,
heartbeat 10 s, reaper 5 s, poll 0.5 s; one 15 s IO job per trial; the failure
came a random 0-10 s after the claim.

## E4: a worker killed with SIGKILL

Spec section 9 guessed recovery "in about 40 seconds" with these settings, and
G3 bounds it by "roughly lease duration + reaper interval".

- Recovery (kill to attempt 2 claimed): **min 25.6 s, median 27.9 s, max 33.8 s**
  over 10 trials. All 10 fall inside the predicted 20-35.5 s (lease minus
  heartbeat interval, to lease plus reaper and poll intervals), and below the
  guess of 40 s.
- Recovery minus the lease left at the kill: 1.3 to 5.2 s, inside the predicted
  0-5.5 s every time. Once a lease runs out, the only delay is the reaper's
  interval plus a poll: the reaper runs on schedule, and a recovered job is
  claimable at once (no backoff, as decided in ADR-006 Q2).
- History correct in all 10: attempt 1 LEASE_EXPIRED by the killed worker,
  attempt 2 SUCCEEDED by the other worker. The job ran twice, as at-least-once
  allows; the killed worker never got to write anything.

## E5: a worker frozen with docker pause

Frozen for 28.5 to 35.1 s each time, until the other worker had claimed the job,
then woken while that worker still owned it. **All checks passed in 10 of 10
trials:** attempt 1 stayed LEASE_EXPIRED (not overwritten), attempt 2 ended
SUCCEEDED by the other worker, and the woken worker logged that it lost the run.

How the stale run was stopped, from the workers' logs (11 occurrences: the 10
trials plus one from the harness's dry run):

- Every time, the woken worker's heartbeat found the lease gone and logged "Lost
  the lease ... interrupting its run".
- Every time, the handler still finished and tried to complete, and the fenced
  write was rejected: "finished, but this worker no longer owns it; result
  discarded".

The interrupt came too late to matter: the handler's 15 s sleep had already run
out during the freeze, so on waking it returned at once. **Fencing, not the
heartbeat's interrupt, is what kept the stale result out.** The interrupt only
saves work when a handler is still mid-run after waking.

This is G5 demonstrated against a real paused process, not only in the
integration tests: a worker that lost ownership could not overwrite the state or
result of the newer attempt.
