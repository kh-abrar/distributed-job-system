#!/usr/bin/env bash
# E1: submission latency (spec section 9: "p95 under 50 ms at around 200 requests per second").
#
# Sends POST /api/jobs at a fixed rate, first with no workers running, then with
# workers draining the queue at the same time (they compete for the database).
# Each mode runs RUNS times on an empty database; summary.md reports every run
# and the median.
#
# Usage (Git Bash or any POSIX shell):
#   experiments/run-e1-latency.sh
# Settings, all optional: RUNS=3 RATE=200 DURATION=2m WARMUP=30s WORKERS_UNDER_LOAD=4
# Takes about 20 minutes with the defaults. Leaves the stack running.

source "$(dirname "$0")/lib.sh"

RUNS=${RUNS:-3}
RATE=${RATE:-200}
DURATION=${DURATION:-2m}
WARMUP=${WARMUP:-30s}
WORKERS_UNDER_LOAD=${WORKERS_UNDER_LOAD:-4}

top=$(new_results_dir e1-latency)
log "E1: $RATE req/s, $WARMUP warm-up + $DURATION measured, $RUNS runs per mode; results in $top"

start_stack

for workers in 0 "$WORKERS_UNDER_LOAD"; do
    for run in $(seq 1 "$RUNS"); do
        dir="$top/workers-$workers/run-$run"
        mkdir -p "$dir"
        log "Mode: $workers workers, run $run of $RUNS"

        scale_workers 0
        reset_db
        if (( workers > 0 )); then
            scale_workers "$workers"
        fi
        capture_env "$dir"
        echo "workers: $workers" >> "$dir/environment.txt"

        run_k6 "$dir" submit.js -e RATE="$RATE" -e DURATION="$DURATION" -e WARMUP="$WARMUP" > /dev/null

        psql_file "$EXP_DIR/sql/statements.sql" > "$dir/statements.txt"
        # Did the workers keep up? Jobs still open when the load stopped.
        psql_exp -c "SELECT status, count(*) FROM jobs GROUP BY status ORDER BY status" > "$dir/queue-at-end.txt"
        log "  $(grep -E 'latency_ms_p95|error_rate' "$dir/latency.txt" | tr '\n' ' ')"
    done
done
scale_workers 0

"$EXP_DIR/summarize-e1.sh" "$top"

log "Done"
cat "$top/summary.md"
