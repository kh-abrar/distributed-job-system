#!/usr/bin/env bash
# Writes summary.md for an E1 results directory, from the files each run saved.
# run-e1-latency.sh calls it at the end; it can also be rerun on old results:
#   experiments/summarize-e1.sh experiments/results/<time>-e1-latency

set -euo pipefail

top=${1:?usage: summarize-e1.sh RESULTS_DIR}

# value_of FILE KEY: the value of "KEY: value" in a latency.txt
value_of() {
    grep "^$2:" "$1" | cut -d' ' -f2 || true
}

# median of the numbers on stdin
median() {
    sort -g | awk '{ v[NR] = $1 } END { print (NR % 2) ? v[(NR + 1) / 2] : (v[NR / 2] + v[NR / 2 + 1]) / 2 }'
}

# Older runs predate iteration_ms_max in latency.txt; read it from the full k6 summary
iteration_max() {
    local run_dir=$1 value
    value=$(value_of "$run_dir/latency.txt" iteration_ms_max)
    if [[ -z "$value" ]]; then
        # The JSON is pretty-printed over many lines; flatten it before matching
        value=$(tr -d ' \n\r' < "$run_dir/k6-summary.json" \
            | grep -o '"iteration_duration":{[^}]*' \
            | grep -o '"max":[0-9.]*' | cut -d: -f2 | xargs printf '%.2f')
    fi
    echo "$value"
}

first_latency=$(ls "$top"/workers-*/run-1/latency.txt | head -1)
{
    echo "# E1: submission latency"
    echo
    echo "$(value_of "$first_latency" rate_per_second) requests/s for $(value_of "$first_latency" measured_duration)" \
         "after a $(value_of "$first_latency" warmup) warm-up. Latencies in milliseconds, measured by k6"
    echo "inside the Docker network. Environment: see any run's environment.txt."
    echo
    echo "\"Slowest iteration\" bounds the slowest request from above. k6's own maximum request"
    echo "time is not shown: it can absorb a clock step in Docker's VM (see notes.md)."
    for mode_dir in "$top"/workers-*; do
        workers=${mode_dir##*/workers-}
        echo
        echo "## $workers workers running"
        echo
        echo "| Run | Requests | p50 | p95 | p99 | Slowest iteration | Error rate | Dropped | Open jobs at end |"
        echo "|---|---|---|---|---|---|---|---|---|"
        for run_dir in "$mode_dir"/run-*; do
            f="$run_dir/latency.txt"
            open=$(awk -F'|' '$1 == "PENDING" || $1 == "RUNNING" { n += $2 } END { print n + 0 }' "$run_dir/queue-at-end.txt")
            echo "| ${run_dir##*/run-} | $(value_of "$f" requests_measured) | $(value_of "$f" latency_ms_p50) | $(value_of "$f" latency_ms_p95) | $(value_of "$f" latency_ms_p99) | $(iteration_max "$run_dir") | $(value_of "$f" error_rate) | $(value_of "$f" dropped_iterations) | $open |"
        done
        echo "| **Median** | | **$(for f in "$mode_dir"/run-*/latency.txt; do value_of "$f" latency_ms_p50; done | median)** | **$(for f in "$mode_dir"/run-*/latency.txt; do value_of "$f" latency_ms_p95; done | median)** | **$(for f in "$mode_dir"/run-*/latency.txt; do value_of "$f" latency_ms_p99; done | median)** | | | | |"
    done
} > "$top/summary.md"
