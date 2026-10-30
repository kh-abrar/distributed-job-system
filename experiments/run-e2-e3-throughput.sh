#!/usr/bin/env bash
# E2 and E3: throughput and scaling (spec section 9).
#
#   E2: "four workers handling at least 500 lightweight (near no-op) jobs per second"
#   E3: "adding workers improves throughput for CPU and IO workloads until some
#        other bottleneck takes over"
#
# Every run: empty database, jobs inserted straight into the table (so the api is
# not part of the measurement) behind a gate, workers started and ready, gate
# opened, queue drained. Throughput comes from counting finished jobs every 0.2 s
# against the host's clock (Docker's VM clock is not reliable, see lib.sh).
#
# Usage (Git Bash or any POSIX shell):
#   experiments/run-e2-e3-throughput.sh            # everything, about 45 minutes
#   experiments/run-e2-e3-throughput.sh e2         # only E2
#   experiments/run-e2-e3-throughput.sh e3-io e3-cpu
# Settings: RUNS=3 E2_JOBS=60000 SCALE_WORKERS="1 2 4 8"
#           IO_JOBS_PER_WORKER=1200 CPU_JOBS_PER_WORKER=2400 CPU_ITERATIONS=500000
# Leaves the stack running.

source "$(dirname "$0")/lib.sh"

RUNS=${RUNS:-3}
# 60,000 so a run lasts long enough (about 20 s) for 0.2 s samples to be precise
E2_JOBS=${E2_JOBS:-60000}
SCALE_WORKERS=${SCALE_WORKERS:-1 2 4 8}
IO_JOBS_PER_WORKER=${IO_JOBS_PER_WORKER:-1200}
CPU_JOBS_PER_WORKER=${CPU_JOBS_PER_WORKER:-2400}
CPU_ITERATIONS=${CPU_ITERATIONS:-500000}

cases=("$@")
if (( ${#cases[@]} == 0 )); then
    cases=(e2 e3-io e3-cpu)
fi

top=$(new_results_dir throughput)
log "Throughput experiments (${cases[*]}), $RUNS runs each; results in $top"
start_stack

# run_case NAME WORKERS JOBS TYPE PAYLOAD
run_case() {
    local name=$1 workers=$2 jobs=$3 type=$4 payload=$5
    for run in $(seq 1 "$RUNS"); do
        local dir="$top/$name/workers-$workers/run-$run"
        mkdir -p "$dir"
        log "$name: $workers workers, $jobs $type jobs, run $run of $RUNS"

        scale_workers 0
        reset_db
        insert_gated_jobs "$jobs" "$type" "$payload"
        scale_workers "$workers"
        wait_for_workers "$workers"
        capture_env "$dir"
        {
            echo "workers: $workers"
            echo "jobs: $jobs $type $payload"
        } >> "$dir/environment.txt"

        # The sampler starts before the gate opens, so it sees the count leave 0
        start_sampler "$dir/samples.txt" "$jobs"
        open_gate
        local gate_ns
        gate_ns=$(host_ns)
        stop_sampler
        wait_for_drain 900
        collect_throughput_metrics "$dir" "$dir/samples.txt" "$jobs" "$gate_ns"
        log "  $(grep -E 'all_succeeded|steady_state|mean_job_ms|implausible|jobs_per_claim' "$dir/metrics.txt" | tr '\n' ' ')"
    done
}

for case_name in "${cases[@]}"; do
    case "$case_name" in
        e2)
            run_case e2-noop 4 "$E2_JOBS" IO '{"sleepMs":0}'
            ;;
        e3-io)
            for w in $SCALE_WORKERS; do
                run_case e3-io "$w" $(( IO_JOBS_PER_WORKER * w )) IO '{"sleepMs":100}'
            done
            ;;
        e3-cpu)
            for w in $SCALE_WORKERS; do
                run_case e3-cpu "$w" $(( CPU_JOBS_PER_WORKER * w )) CPU "{\"iterations\":$CPU_ITERATIONS}"
            done
            ;;
        *)
            echo "Unknown case: $case_name (expected e2, e3-io or e3-cpu)" >&2
            exit 1
            ;;
    esac
done
scale_workers 0

"$EXP_DIR/summarize-throughput.sh" "$top"
log "Done"
cat "$top/summary.md"
