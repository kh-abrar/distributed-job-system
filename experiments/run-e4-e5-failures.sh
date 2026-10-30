#!/usr/bin/env bash
# E4 and E5: worker failures (spec sections 7 and 9).
#
#   E4: a worker is killed with SIGKILL mid-job. Spec section 9 guessed recovery
#       "in about 40 seconds"; G3 bounds it by lease + reaper interval.
#   E5: a worker freezes (docker pause, like SIGSTOP) mid-job. Its lease expires,
#       another worker takes the job over, then the frozen worker wakes up holding
#       a stale claim. Fencing must reject anything it writes (G5).
#
# Each trial: empty database, 2 workers with the spec's settings (30 s lease,
# 10 s heartbeat, 5 s reaper, 0.5 s poll), one IO job. The failure comes a random
# 0-10 s after the claim, so it lands at a random point between heartbeats. All
# timing uses the host's clock.
#
# Usage (Git Bash or any POSIX shell):
#   experiments/run-e4-e5-failures.sh            # both, about 25 minutes
#   experiments/run-e4-e5-failures.sh e4         # or only one
# Settings: TRIALS=10 JOB_SLEEP_MS=15000
# Leaves the stack running.

source "$(dirname "$0")/lib.sh"

TRIALS=${TRIALS:-10}
JOB_SLEEP_MS=${JOB_SLEEP_MS:-15000}
LEASE_S=30
REAPER_S=5
POLL_S=0.5
HEARTBEAT_S=10

cases=("$@")
if (( ${#cases[@]} == 0 )); then
    cases=(e4 e5)
fi

top=$(new_results_dir failures)
log "Failure experiments (${cases[*]}), $TRIALS trials each; results in $top"
start_stack
capture_env "$top"

# Two running workers, booted and ready. A killed worker's container is started again.
ensure_two_workers() {
    local before
    before=$(exp_compose ps --status running --format '{{.Name}}' worker | grep -c . || true)
    if (( before < 2 )); then
        scale_workers 2
        # Give a (re)started JVM time to boot and start polling
        sleep 12
    fi
}

# Submits one IO job and waits until a worker has claimed it. Prints the job id.
claimed_job() {
    local id
    id=$(psql_exp -c "INSERT INTO jobs (type, payload) VALUES ('IO', '{\"sleepMs\":$JOB_SLEEP_MS}') RETURNING id")
    for _ in $(seq 1 100); do
        [[ -n "$(owner_container "$id")" ]] && { echo "$id"; return 0; }
        sleep 0.1
    done
    echo "Job $id was not claimed within 10 s" >&2
    return 1
}

# lease_left_at JOB_ID HOST_NS: seconds of lease the job had left at that host time.
# Compares PostgreSQL's lease_expires_at with the host's clock, which is valid only
# because the two clocks agree on this machine (see results/*-clock); environment.txt
# records the skew for each run.
lease_left_at() {
    local expires
    expires=$(psql_exp -c "SELECT extract(epoch FROM lease_expires_at) FROM jobs WHERE id = '$1'")
    awk -v e="$expires" -v t="$2" 'BEGIN { printf "%.2f", e - t / 1e9 }'
}

history_of() {
    psql_exp -c "SELECT string_agg(attempt || ':' || coalesce(outcome, 'open') || ':' || left(worker_id, 12), ' '
                                   ORDER BY attempt)
                 FROM job_attempts WHERE job_id = '$1'"
}

seconds_between() {
    awk -v a="$1" -v b="$2" 'BEGIN { printf "%.2f", (b - a) / 1e9 }'
}

random_delay() {
    awk -v r="$RANDOM" 'BEGIN { printf "%.2f", r / 32767 * 10 }'
}

run_e4() {
    local out="$top/e4-kill.tsv"
    printf 'trial\tdelay_s\tlease_left_s\trecovery_s\trecovery_minus_lease_s\thistory\tok\n' > "$out"
    for trial in $(seq 1 "$TRIALS"); do
        ensure_two_workers
        reset_db
        local id victim delay before after left reclaim recovery history ok
        id=$(claimed_job)
        victim=$(owner_container "$id")
        delay=$(random_delay)
        sleep "$delay"

        before=$(host_ns)
        docker kill "$victim" >/dev/null
        after=$(host_ns)
        # The kill happened somewhere inside the docker kill call: use its midpoint
        local killed_at=$(( (before + after) / 2 ))
        # A dead worker can't renew its lease, so reading it after the kill is safe
        left=$(lease_left_at "$id" "$killed_at")

        reclaim=$(wait_for_count "SELECT count(*) FROM job_attempts WHERE job_id = '$id' AND attempt = 2" 1 90)
        recovery=$(seconds_between "$killed_at" "$reclaim")
        wait_for_count "SELECT count(*) FROM jobs WHERE id = '$id' AND status = 'SUCCEEDED'" 1 60 >/dev/null
        history=$(history_of "$id")

        ok=yes
        [[ -z "$reclaim" ]] && ok="no: not recovered"
        [[ "$history" == 1:LEASE_EXPIRED:"$victim"\ 2:SUCCEEDED:* ]] || ok="no: history $history"
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$trial" "$delay" "$left" "$recovery" \
            "$(awk -v r="$recovery" -v l="$left" 'BEGIN { printf "%.2f", r - l }')" "$history" "$ok" >> "$out"
        log "E4 trial $trial: killed after ${delay}s, ${left}s of lease left, recovered in ${recovery}s ($ok)"
    done
}

run_e5() {
    local out="$top/e5-freeze.tsv"
    printf 'trial\tdelay_s\tfrozen_s\tstale_write_rejected\thistory\tok\n' > "$out"
    for trial in $(seq 1 "$TRIALS"); do
        ensure_two_workers
        reset_db
        local id victim delay paused reclaim frozen history rejected ok
        id=$(claimed_job)
        victim=$(owner_container "$id")
        delay=$(random_delay)
        sleep "$delay"

        docker pause "$victim" >/dev/null
        paused=$(host_ns)
        reclaim=$(wait_for_count "SELECT count(*) FROM job_attempts WHERE job_id = '$id' AND attempt = 2" 1 90)
        # Wake the frozen worker while the other one owns the job. Its handler's sleep
        # deadline has long passed, so it tries to complete straight away.
        docker unpause "$victim" >/dev/null
        frozen=$(seconds_between "$paused" "$(host_ns)")

        wait_for_count "SELECT count(*) FROM jobs WHERE id = '$id' AND status = 'SUCCEEDED'" 1 60 >/dev/null
        # Give the woken worker a moment to log what happened to its stale run
        sleep 3
        history=$(history_of "$id")
        if docker logs "$victim" 2>&1 | grep -E "$id" | grep -qE "no longer owns it|Lost the lease"; then
            rejected=yes
        else
            rejected="no log line"
        fi

        ok=yes
        [[ -z "$reclaim" ]] && ok="no: not recovered"
        [[ "$history" == 1:LEASE_EXPIRED:"$victim"\ 2:SUCCEEDED:* ]] || ok="no: history $history"
        [[ "$rejected" == yes ]] || ok="no: $rejected"
        printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$trial" "$delay" "$frozen" "$rejected" "$history" "$ok" >> "$out"
        log "E5 trial $trial: frozen after ${delay}s for ${frozen}s; stale write rejected: $rejected ($ok)"
    done
}

for case_name in "${cases[@]}"; do
    case "$case_name" in
        e4) run_e4 ;;
        e5) run_e5 ;;
        *) echo "Unknown case: $case_name (expected e4 or e5)" >&2; exit 1 ;;
    esac
done

"$EXP_DIR/summarize-failures.sh" "$top" "$LEASE_S" "$HEARTBEAT_S" "$REAPER_S" "$POLL_S"
log "Done"
cat "$top/summary.md"
