#!/usr/bin/env bash
# Writes summary.md for a failures results directory (E4, E5), from the per-trial
# .tsv files. run-e4-e5-failures.sh calls it at the end; it can also be rerun:
#   experiments/summarize-failures.sh RESULTS_DIR [LEASE_S HEARTBEAT_S REAPER_S POLL_S]

set -euo pipefail

top=${1:?usage: summarize-failures.sh RESULTS_DIR [LEASE_S HEARTBEAT_S REAPER_S POLL_S]}
lease=${2:-30}
heartbeat=${3:-10}
reaper=${4:-5}
poll=${5:-0.5}

# A trial's recovery should be: lease left at the kill, plus up to one reaper
# interval for the reaper to notice, plus up to one poll interval for a worker to claim.
{
    echo "# Worker failures (E4, E5)"
    echo
    echo "Worker settings: lease ${lease} s, heartbeat ${heartbeat} s, reaper ${reaper} s, poll ${poll} s."
    echo "Times from the host's clock. Environment: environment.txt."

    if [[ -f "$top/e4-kill.tsv" ]]; then
        echo
        echo "## E4: worker killed with SIGKILL"
        echo
        echo "Recovery is the time from the kill until another worker claimed the job"
        echo "(attempt 2). Predicted for any kill: $(awk -v l="$lease" -v h="$heartbeat" 'BEGIN { print l - h }')"
        echo "to $(awk -v l="$lease" -v r="$reaper" -v p="$poll" 'BEGIN { print l + r + p }') s (lease minus heartbeat"
        echo "interval, to lease plus reaper and poll intervals). Per trial, recovery minus"
        echo "the lease left at the kill should be between 0 and $(awk -v r="$reaper" -v p="$poll" 'BEGIN { print r + p }') s."
        echo
        echo "| Trial | Kill after claim (s) | Lease left (s) | **Recovery (s)** | Recovery − lease left (s) | OK |"
        echo "|---|---|---|---|---|---|"
        tail -n +2 "$top/e4-kill.tsv" | awk -F'\t' '{ printf "| %s | %s | %s | **%s** | %s | %s |\n", $1, $2, $3, $4, $5, $7 }'
        echo
        # Sorted by recovery time, so min, median and max can be read off by position
        tail -n +2 "$top/e4-kill.tsv" | sort -t$'\t' -k4,4g | awk -F'\t' -v l="$lease" -v h="$heartbeat" -v r="$reaper" -v p="$poll" '
            { s[NR] = $4; if ($7 == "yes") ok++
              if ($4 >= l - h && $4 <= l + r + p) inside++
              if ($5 >= -0.5 && $5 <= r + p + 0.5) tight++ }
            END {
                n = NR
                med = (n % 2) ? s[(n + 1) / 2] : (s[n / 2] + s[n / 2 + 1]) / 2
                printf "Recovery: min %.2f s, median %.2f s, max %.2f s over %d trials.\n\n", s[1], med, s[n], n
                printf "- Within the predicted %g-%g s: %d of %d\n", l - h, l + r + p, inside, n
                printf "- Recovery − lease left within 0-%g s (±0.5 s for measuring): %d of %d\n", r + p, tight, n
                printf "- History correct (dead worker LEASE_EXPIRED, other worker SUCCEEDED): %d of %d\n", ok + 0, n
            }'
    fi

    if [[ -f "$top/e5-freeze.tsv" ]]; then
        echo
        echo "## E5: worker frozen with docker pause"
        echo
        echo "The owner is frozen until another worker has taken the job over, then woken."
        echo "Its stale write must be rejected: attempt 1 stays LEASE_EXPIRED, attempt 2 is"
        echo "SUCCEEDED by the other worker, and the woken worker logs that it lost the run."
        echo
        echo "| Trial | Freeze after claim (s) | Frozen for (s) | Stale write rejected | History | OK |"
        echo "|---|---|---|---|---|---|"
        tail -n +2 "$top/e5-freeze.tsv" | awk -F'\t' '{ printf "| %s | %s | %s | %s | `%s` | %s |\n", $1, $2, $3, $4, $5, $6 }'
        echo
        tail -n +2 "$top/e5-freeze.tsv" | awk -F'\t' '
            { if ($6 == "yes") ok++ }
            END { printf "All checks passed: %d of %d trials.\n", ok, NR }'
    fi
} > "$top/summary.md"
