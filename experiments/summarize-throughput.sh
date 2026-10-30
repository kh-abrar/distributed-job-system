#!/usr/bin/env bash
# Writes summary.md for a throughput results directory (E2 and E3), from each
# run's metrics.txt. run-e2-e3-throughput.sh calls it at the end; it can also be
# rerun on old results:
#   experiments/summarize-throughput.sh experiments/results/<time>-throughput

set -euo pipefail

top=${1:?usage: summarize-throughput.sh RESULTS_DIR}

value_of() {
    grep "^$2:" "$1" | cut -d' ' -f2- || true
}

median() {
    sort -g | awk '{ v[NR] = $1 } END { if (NR == 0) print "n/a"; else print (NR % 2) ? v[(NR + 1) / 2] : (v[NR / 2] + v[NR / 2 + 1]) / 2 }'
}

sum() {
    awk '{ s += $1 } END { print s + 0 }'
}

title_of() {
    case "$1" in
        e2-noop) echo "E2: near no-op jobs (IO, sleepMs 0)" ;;
        e3-io)   echo "E3: IO jobs (sleepMs 100)" ;;
        e3-cpu)  echo "E3: CPU jobs" ;;
        *)       echo "$1" ;;
    esac
}

{
    echo "# Throughput (E2, E3)"
    echo
    echo "Jobs per second, timed with the host's clock: finished jobs were counted"
    echo "every 0.2 s. \"Steady\" is the rate between 10% and 90% of the jobs finishing,"
    echo "leaving out ramp-up and the tail; \"overall\" runs from opening the gate to the"
    echo "last job. Each worker has the default 4 handler threads."
    echo
    echo "\"Mean job ms\" comes from database timestamps, keeping only rows between 0 and"
    echo "60 s; \"Dropped rows\" counts the others, which show Docker's VM clock jumping."
    echo "\"Jobs per claim\" divides claimed jobs by claim statements, including polls that"
    echo "found nothing; below 1 means more polls than jobs."
    echo "Environment: see any run's environment.txt."
    for case_dir in "$top"/*/; do
        case_dir=${case_dir%/}
        name=${case_dir##*/}
        [[ -d "$case_dir" && "$name" == e* ]] || continue
        echo
        echo "## $(title_of "$name")"
        echo
        echo "| Workers | Jobs | Steady jobs/s per run | **Median steady jobs/s** | Median overall jobs/s | Mean job ms | Dropped rows | Jobs per claim | Not succeeded |"
        echo "|---|---|---|---|---|---|---|---|---|"
        for workers_dir in $(ls -d "$case_dir"/workers-* | sort -t- -k2 -n); do
            workers=${workers_dir##*/workers-}
            metrics=("$workers_dir"/run-*/metrics.txt)
            jobs=$(value_of "${metrics[0]}" succeeded_jobs)
            per_run=$(for m in "${metrics[@]}"; do value_of "$m" steady_state_jobs_per_second; done | paste -sd' ' | sed 's/ /, /g')
            steady=$(for m in "${metrics[@]}"; do value_of "$m" steady_state_jobs_per_second; done | median)
            overall=$(for m in "${metrics[@]}"; do value_of "$m" overall_jobs_per_second; done | median)
            job_ms=$(for m in "${metrics[@]}"; do value_of "$m" mean_job_ms; done | grep -v n/a | median)
            dropped=$(for m in "${metrics[@]}"; do value_of "$m" implausible_duration_rows; done | sum)
            per_claim=$(for m in "${metrics[@]}"; do value_of "$m" jobs_per_claim; done | median)
            failed=$(for m in "${metrics[@]}"; do value_of "$m" not_succeeded_jobs; done | sum)
            echo "| $workers | $jobs | $per_run | **$steady** | $overall | $job_ms | $dropped | $per_claim | $failed |"
        done
    done
} > "$top/summary.md"
