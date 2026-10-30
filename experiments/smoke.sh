#!/usr/bin/env bash
# Checks that the experiment harness works end to end, in about a minute:
# starts the experiment stack, bulk-inserts jobs, drains them with one worker,
# runs a short k6 load, and collects every metric into results/<time>-smoke/.
#
# Usage (Git Bash or any POSIX shell, from anywhere):
#   experiments/smoke.sh
# Leaves the stack running; stop it with: experiments/stop.sh

source "$(dirname "$0")/lib.sh"

results=$(new_results_dir smoke)
log "Results in $results"

start_stack
scale_workers 0
reset_db
capture_env "$results"

insert_jobs 200 IO '{"sleepMs":0}'
scale_workers 1
wait_for_drain 120
scale_workers 0
collect_db_metrics "$results"

reset_db
log "Running a 5 s k6 load at 20 requests/s"
run_k6 "$results" submit.js -e RATE=20 -e WARMUP=2s -e DURATION=5s

log "Done. Files:"
ls -1 "$results"
