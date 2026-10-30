#!/usr/bin/env bash
# Watches PostgreSQL's clock against the host's, to see whether Docker Desktop's
# VM clock steps backwards (and for how long). The jobs system schedules and
# expires leases with PostgreSQL's now() (spec Assumption 3), so a backward step
# would make claimable jobs look "in the future" until the clock comes back.
#
# Usage: experiments/clock-monitor.sh [SECONDS]   (default 600; needs the stack running)
# Writes results/<time>-clock/offsets.txt ("<host ns> <db minus host, seconds>")
# and summary.md listing every jump of more than 1 s.

source "$(dirname "$0")/lib.sh"

duration=${1:-600}
dir=$(new_results_dir clock)
capture_env "$dir"
log "Watching PostgreSQL's clock for $duration s; results in $dir"

samples=$(( duration * 5 ))
printf 'SELECT extract(epoch FROM clock_timestamp()) \\watch 0.2\n' \
    | timeout "$((duration + 30))" docker exec -i -e PGAPPNAME=clock-monitor "$PG_CONTAINER" \
        psql -U jobs -d jobs -tA 2>/dev/null \
    | while IFS= read -r line; do
        [[ "$line" =~ ^[0-9]+\.[0-9]+$ ]] || continue
        now_ns=$(host_ns)
        awk -v db="$line" -v host="$now_ns" 'BEGIN { printf "%s %.3f\n", host, db - host / 1e9 }'
        samples=$(( samples - 1 ))
        (( samples <= 0 )) && break
    done > "$dir/offsets.txt" || true  # stopping early breaks the pipe, which pipefail reports as a failure
psql_exp -c "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity
             WHERE application_name = 'clock-monitor'" >/dev/null

# A jump is a change of more than 1 s in the offset between two samples
awk -v start="$(head -1 "$dir/offsets.txt" | cut -d' ' -f1)" '
    NR > 1 && ($2 - prev > 1 || prev - $2 > 1) {
        printf "| %.1f | %+.1f | %.1f |\n", ($1 - start) / 1e9, $2 - prev, $2
    }
    { prev = $2 }
' "$dir/offsets.txt" > "$dir/jumps.txt"

{
    echo "# Clock monitor"
    echo
    echo "PostgreSQL's clock_timestamp() minus the host's clock, sampled every 0.2 s"
    echo "for $duration s ($(wc -l < "$dir/offsets.txt") samples)."
    echo
    echo "Offset at start: $(head -1 "$dir/offsets.txt" | cut -d' ' -f2) s; at end: $(tail -1 "$dir/offsets.txt" | cut -d' ' -f2) s."
    echo
    if [[ -s "$dir/jumps.txt" ]]; then
        echo "| Seconds into the run | Jump (s) | Offset after (s) |"
        echo "|---|---|---|"
        cat "$dir/jumps.txt"
    else
        echo "No jumps larger than 1 s."
    fi
} > "$dir/summary.md"
rm -f "$dir/jumps.txt"
cat "$dir/summary.md"
