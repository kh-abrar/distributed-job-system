#!/usr/bin/env bash
# E6: duplicate execution (spec section 7, row 3; ADR-003: duplicates "have to be
# tested on purpose").
#
# Two workers run with fault injection on: after a handler succeeds, a worker
# exits (Runtime.halt) with probability CRASH_PROBABILITY, before recording the
# result. Docker restarts it. The job's lease runs out, the reaper recovers it, and
# a worker runs it again. Each crash also cuts short whatever else that worker
# was running.
#
# Questions answered:
# - Is every job still done (G2, at-least-once), despite the crashes?
# - How many jobs ran their handler to the end more than once (duplicates)?
# - Does each duplicate show up in job_attempts, and does every run have its own
#   (job_id, generation, attempt), so a handler could tell repeats apart?
#
# Usage: experiments/run-e6-duplicates.sh
# Settings: JOBS=400 CRASH_PROBABILITY=0.04 JOB_SLEEP_MS=200
# Takes about 5-10 minutes. Leaves the stack running.

source "$(dirname "$0")/lib.sh"

JOBS=${JOBS:-400}
CRASH_PROBABILITY=${CRASH_PROBABILITY:-0.04}
JOB_SLEEP_MS=${JOB_SLEEP_MS:-200}

dir=$(new_results_dir duplicates)
log "E6: $JOBS IO jobs, crash probability $CRASH_PROBABILITY after each successful handler; results in $dir"
start_stack
# Fresh worker containers, so their logs hold only this run
scale_workers 0
reset_db
capture_env "$dir"
{
    echo "jobs: $JOBS IO {\"sleepMs\":$JOB_SLEEP_MS}"
    echo "crash_probability: $CRASH_PROBABILITY"
    echo "workers: 2 (restart: on-failure)"
} >> "$dir/environment.txt"

insert_jobs "$JOBS" IO "{\"sleepMs\":$JOB_SLEEP_MS}"
scale_workers 2 WORKER_FAULT_CRASHAFTERHANDLERPROBABILITY="$CRASH_PROBABILITY" WORKER_RESTART=on-failure
started=$(host_ns)
# Proof the switch is really on: the worker logs a warning at startup when it is
sleep 10
if ! exp_compose logs worker 2>&1 | grep -q "FAULT INJECTION ENABLED"; then
    echo "Fault injection is not enabled in the workers; is the image up to date?" >&2
    exit 1
fi
wait_for_drain 1800
log "All jobs finished in $(awk -v s="$started" -v e="$(host_ns)" 'BEGIN { printf "%.0f", (e - s) / 1e9 }') s"

# Every injected crash, from the workers' logs: "<job id> <generation> <attempt>"
for container in $(exp_compose ps -a --format '{{.Name}}' worker); do
    # || true: a worker that never crashed has no such lines, and pipefail would stop the script
    docker logs "$container" 2>&1 | grep "FAULT INJECTION: job" \
        | sed -E 's/.*job ([0-9a-f-]+) generation ([0-9]+) attempt ([0-9]+).*/\1 \2 \3/' || true
    echo "$container restarts: $(docker inspect -f '{{.RestartCount}}' "$container")" >> "$dir/restarts.txt"
done > "$dir/crashes.txt"
crashes=$(grep -c . "$dir/crashes.txt" || true)
log "$crashes injected crashes"

# The crashed runs as a SQL VALUES list, to join against the history
values=$(awk '{ printf "%s(%s::uuid, %s, %s)", (NR > 1 ? ", " : ""), "'"'"'" $1 "'"'"'", $2, $3 }' "$dir/crashes.txt")
[[ -z "$values" ]] && values="(NULL::uuid, NULL::int, NULL::int)"

psql_exp > "$dir/metrics.txt" <<SQL
WITH crashed(job_id, generation, attempt) AS (VALUES $values),
crashed_runs AS (
    SELECT c.job_id, a.outcome,
           EXISTS (SELECT 1 FROM job_attempts later
                   WHERE later.job_id = c.job_id AND later.outcome = 'SUCCEEDED'
                     AND (later.generation, later.attempt) > (c.generation, c.attempt)) AS succeeded_later
    FROM crashed c
    LEFT JOIN job_attempts a USING (job_id, generation, attempt)
    WHERE c.job_id IS NOT NULL
)
SELECT 'jobs: ' || (SELECT count(*) FROM jobs) || E'\n' ||
       'jobs_succeeded: ' || (SELECT count(*) FROM jobs WHERE status = 'SUCCEEDED') || E'\n' ||
       'jobs_dead: ' || (SELECT count(*) FROM jobs WHERE status = 'DEAD') || E'\n' ||
       'jobs_other: ' || (SELECT count(*) FROM jobs WHERE status NOT IN ('SUCCEEDED', 'DEAD')) || E'\n' ||
       'runs: ' || (SELECT count(*) FROM job_attempts) || E'\n' ||
       'runs_distinct_by_job_generation_attempt: ' ||
           (SELECT count(DISTINCT (job_id, generation, attempt)) FROM job_attempts) || E'\n' ||
       'jobs_run_more_than_once: ' ||
           (SELECT count(*) FROM (SELECT job_id FROM job_attempts GROUP BY job_id HAVING count(*) > 1) x) || E'\n' ||
       'lease_expired_runs: ' || (SELECT count(*) FROM job_attempts WHERE outcome = 'LEASE_EXPIRED') || E'\n' ||
       'crashed_runs: ' || (SELECT count(*) FROM crashed_runs) || E'\n' ||
       'crashed_runs_recorded_as_lease_expired: ' ||
           (SELECT count(*) FROM crashed_runs WHERE outcome = 'LEASE_EXPIRED') || E'\n' ||
       'duplicate_completions: ' || (SELECT count(*) FROM crashed_runs WHERE succeeded_later) || E'\n' ||
       'runs_cut_short_by_a_crash: ' ||
           ((SELECT count(*) FROM job_attempts WHERE outcome = 'LEASE_EXPIRED')
            - (SELECT count(*) FROM crashed_runs WHERE outcome = 'LEASE_EXPIRED')) || E'\n' ||
       'max_attempts_used_by_one_job: ' || (SELECT max(attempts) FROM jobs)
SQL

{
    echo "# E6: duplicate execution"
    echo
    echo "$JOBS IO jobs (sleepMs $JOB_SLEEP_MS), 2 workers. After each successful handler a worker"
    echo "exits with probability $CRASH_PROBABILITY, before recording the result; Docker restarts it."
    echo
    echo "| Measure | Value |"
    echo "|---|---|"
    sed -E 's/^([a-z_]+): (.*)$/| \1 | \2 |/' "$dir/metrics.txt"
    echo
    echo "- **crashed_runs**: handlers that finished, then lost their result to an injected crash"
    echo "- **duplicate_completions**: of those, jobs whose handler later finished again and succeeded"
    echo "- **runs_cut_short_by_a_crash**: other runs on a crashing worker, stopped mid-handler"
    echo
    echo "Restarts per worker container:"
    echo
    sed 's/^/- /' "$dir/restarts.txt"
} > "$dir/summary.md"

# Leave the workers in their normal configuration
scale_workers 0
log "Done"
cat "$dir/summary.md"
