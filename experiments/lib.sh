#!/usr/bin/env bash
# Shared helpers for the Phase 5 experiment scripts. Source it, don't run it:
#   source "$(dirname "$0")/lib.sh"
#
# Written for Git Bash on Windows and for Linux/macOS shells.

set -euo pipefail

EXP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$EXP_DIR/.." && pwd)"
PROJECT=jobs-exp
K6_IMAGE=grafana/k6:1.0.0
PG_CONTAINER=${PROJECT}-postgres-1

# Docker on Git Bash needs Windows-style paths for bind mounts
host_path() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi
}

exp_compose() {
    docker compose -p "$PROJECT" \
        -f "$REPO_DIR/docker-compose.yml" \
        -f "$EXP_DIR/compose.experiments.yml" "$@"
}

# Runs SQL against the experiment database; prints unaligned, tuple-only output
psql_exp() {
    exp_compose exec -T postgres psql -U jobs -d jobs -v ON_ERROR_STOP=1 -tA "$@"
}

# psql_file SQL_FILE [psql options...]: runs a file of SQL, with normal table output
psql_file() {
    local file=$1
    shift
    exp_compose exec -T postgres psql -U jobs -d jobs -v ON_ERROR_STOP=1 "$@" -f - < "$file"
}

# Runs a docker compose command quietly, but shows its output if it fails
quietly() {
    local output
    if ! output=$("$@" 2>&1); then
        echo "$output" >&2
        return 1
    fi
}

log() {
    echo "[$(date +%H:%M:%S)] $*"
}

# Builds the images and starts postgres and the api, with no workers.
# The api runs the migrations; the function returns once it answers.
start_stack() {
    log "Starting postgres and api (project $PROJECT)"
    # Naming only postgres and api leaves the workers off
    quietly exp_compose up -d --build postgres api
    for _ in $(seq 1 90); do
        if exp_compose exec -T api true 2>/dev/null \
            && psql_exp -c "SELECT 1 FROM flyway_schema_history WHERE version = '3'" 2>/dev/null | grep -q 1; then
            psql_exp -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements" >/dev/null
            log "Stack ready"
            return 0
        fi
        sleep 1
    done
    echo "The api did not finish migrating within 90 s" >&2
    return 1
}

stop_stack() {
    quietly exp_compose down
}

# Runs this many workers (0 stops them all). Extra settings: "KEY=value" pairs
# passed as environment variables, e.g. WORKER_THREADS=8.
scale_workers() {
    local count=$1
    shift
    log "Scaling workers to $count $*"
    quietly env "$@" docker compose -p "$PROJECT" \
        -f "$REPO_DIR/docker-compose.yml" -f "$EXP_DIR/compose.experiments.yml" \
        up -d --no-deps --scale worker="$count" worker
}

# Empties the job tables and the statement statistics, so each run starts clean
reset_db() {
    psql_exp -c "TRUNCATE job_attempts, jobs" >/dev/null
    psql_exp -c "SELECT pg_stat_statements_reset()" >/dev/null
    log "Database reset"
}

# insert_jobs COUNT TYPE PAYLOAD_JSON
# Bulk-inserts PENDING jobs in one statement, the way the api would store them,
# so the api is not part of throughput measurements.
insert_jobs() {
    local count=$1 type=$2 payload=$3
    psql_exp -c "INSERT INTO jobs (type, payload)
                 SELECT '$type', '$payload'::jsonb FROM generate_series(1, $count)" >/dev/null
    log "Inserted $count $type jobs"
}

# insert_gated_jobs COUNT TYPE PAYLOAD_JSON
# Like insert_jobs, but nobody can claim the jobs until open_gate: run_at is an hour
# ahead. This lets every worker finish starting before the first claim, so a worker
# that boots faster doesn't drain jobs alone and skew the throughput.
insert_gated_jobs() {
    local count=$1 type=$2 payload=$3
    psql_exp -c "INSERT INTO jobs (type, payload, run_at)
                 SELECT '$type', '$payload'::jsonb, now() + interval '1 hour'
                 FROM generate_series(1, $count)" >/dev/null
    log "Inserted $count gated $type jobs"
}

# Also resets the statement statistics, so the empty polls made while the gate
# was closed don't count
open_gate() {
    psql_exp -c "SELECT pg_stat_statements_reset()" >/dev/null
    psql_exp -c "UPDATE jobs SET run_at = now() WHERE status = 'PENDING'" >/dev/null
    log "Gate open"
}

# Waits until COUNT worker containers have logged that they started
wait_for_workers() {
    local count=$1
    for _ in $(seq 1 120); do
        local started
        started=$(exp_compose logs worker 2>/dev/null | grep -c "started with" || true)
        if (( started >= count )); then
            log "$started workers started"
            return 0
        fi
        sleep 1
    done
    echo "Only $started of $count workers started within 120 s" >&2
    return 1
}

# Host time in nanoseconds. Timing uses the host's clock, never the VM's:
# Docker Desktop's VM clock can step backwards by minutes (see the notes in
# results/20261030T060120Z-throughput), so PostgreSQL's now() can't time a run.
host_ns() {
    date +%s%N
}

# start_sampler OUT_FILE TARGET
# In the background, records "<host ns> <succeeded jobs>" about every 0.2 s,
# stamping each count with the host clock as it arrives. One psql session runs
# the count with \watch, so there's no per-sample docker exec overhead. Stops
# reading when TARGET jobs have succeeded, or after 15 minutes. Sets SAMPLER_PID.
# Call stop_sampler afterwards: see there why.
start_sampler() {
    local out=$1 target=$2
    (
        printf 'SELECT count(*) FROM jobs WHERE status = %s \\watch 0.2\n' "'SUCCEEDED'" \
            | timeout 900 docker exec -i -e PGAPPNAME=throughput-sampler "$PG_CONTAINER" \
                psql -U jobs -d jobs -tA 2>/dev/null \
            | while IFS= read -r line; do
                [[ "$line" =~ ^[0-9]+$ ]] || continue
                echo "$(host_ns) $line"
                (( line >= target )) && break
            done
    ) > "$out" &
    SAMPLER_PID=$!
}

# Waits for the sampler, then ends its psql session from the database side.
# When the host side of docker exec goes away, the psql inside the container
# keeps running and holds its connection; without this, every run leaked one.
stop_sampler() {
    wait "$SAMPLER_PID" || true
    psql_exp -c "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity
                 WHERE application_name = 'throughput-sampler'" >/dev/null
}

# rates_from_samples SAMPLES_FILE TARGET GATE_NS
# Prints "key: value" lines. Steady state: the time between the count crossing
# 10% and 90% of TARGET, interpolated between samples. Overall: gate to last job.
rates_from_samples() {
    awk -v n="$2" -v gate="$3" '
        { t[NR] = $1; c[NR] = $2 }
        function cross(level,    i) {
            for (i = 2; i <= NR; i++)
                if (c[i] >= level && c[i - 1] < level)
                    return t[i - 1] + (level - c[i - 1]) * (t[i] - t[i - 1]) / (c[i] - c[i - 1])
            return 0
        }
        END {
            done = (NR > 0 && c[NR] >= n)
            t10 = cross(0.1 * n); t90 = cross(0.9 * n)
            printf "samples: %d\n", NR
            printf "all_succeeded: %s\n", done ? "yes" : "no"
            if (done) printf "overall_seconds: %.2f\n", (t[NR] - gate) / 1e9
            if (done) printf "overall_jobs_per_second: %.1f\n", n / ((t[NR] - gate) / 1e9)
            if (t10 && t90 > t10) printf "steady_state_jobs_per_second: %.1f\n", 0.8 * n / ((t90 - t10) / 1e9)
        }' "$1"
}

# collect_throughput_metrics RESULTS_DIR SAMPLES_FILE TARGET GATE_NS
# Writes metrics.txt ("key: value" lines, for the summary script) plus the
# statement statistics and history size. Rates come from the host-clock samples.
# Job durations come from the database, keeping only physically plausible rows
# (0 to 60 s); the number dropped also shows how often the VM clock jumped.
collect_throughput_metrics() {
    local results=$1 samples=$2 target=$3 gate_ns=$4
    collect_db_metrics "$results"
    {
        rates_from_samples "$samples" "$target" "$gate_ns"
        psql_exp <<'SQL'
WITH runs AS (
    SELECT extract(epoch FROM a.finished_at - a.started_at) AS seconds
    FROM job_attempts a JOIN jobs j ON j.id = a.job_id
    WHERE j.status = 'SUCCEEDED' AND a.outcome = 'SUCCEEDED'
), claims AS (
    SELECT coalesce(sum(calls), 0) AS calls, coalesce(sum(rows), 0) AS claimed
    FROM pg_stat_statements
    WHERE query LIKE 'WITH next AS%'
)
SELECT 'succeeded_jobs: ' || (SELECT count(*) FROM runs) || E'\n' ||
       'not_succeeded_jobs: ' || (SELECT count(*) FROM jobs WHERE status <> 'SUCCEEDED') || E'\n' ||
       'mean_job_ms: ' || coalesce(round((SELECT avg(seconds) * 1000 FROM runs WHERE seconds BETWEEN 0 AND 60)::numeric, 1)::text, 'n/a') || E'\n' ||
       'implausible_duration_rows: ' || (SELECT count(*) FROM runs WHERE seconds NOT BETWEEN 0 AND 60) || E'\n' ||
       'claim_statements: ' || claims.calls || E'\n' ||
       'jobs_per_claim: ' || round((claims.claimed::numeric / nullif(claims.calls, 0)), 2)
FROM claims;
SQL
    } > "$results/metrics.txt"
}

# Waits until no job is PENDING or RUNNING, or the timeout passes
wait_for_drain() {
    local timeout_s=${1:-600}
    local start=$SECONDS
    while (( SECONDS - start < timeout_s )); do
        local open
        open=$(psql_exp -c "SELECT count(*) FROM jobs WHERE status IN ('PENDING', 'RUNNING')")
        if [[ "$open" == "0" ]]; then
            log "Queue drained after $((SECONDS - start)) s"
            return 0
        fi
        sleep 1
    done
    echo "Queue not drained after $timeout_s s" >&2
    return 1
}

# Makes a fresh results directory: results/<UTC timestamp>-<name>; prints its path
new_results_dir() {
    local dir
    dir="$EXP_DIR/results/$(date -u +%Y%m%dT%H%M%SZ)-$1"
    mkdir -p "$dir"
    echo "$dir"
}

# Records what the numbers depend on: code version, machine, Docker resources
capture_env() {
    local out=$1/environment.txt
    {
        echo "date_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "git_commit: $(git -C "$REPO_DIR" rev-parse --short HEAD)"
        echo "git_dirty: $(git -C "$REPO_DIR" status --porcelain | grep -q . && echo yes || echo no)"
        echo "docker: $(docker info --format 'cpus={{.NCPU}} memory_bytes={{.MemTotal}} os={{.OperatingSystem}} kernel={{.KernelVersion}}')"
        if command -v powershell.exe >/dev/null 2>&1; then
            echo "host_cpu: $(powershell.exe -NoProfile -Command '(Get-CimInstance Win32_Processor | Select-Object -First 1).Name' | tr -d '\r')"
            echo "host_memory_bytes: $(powershell.exe -NoProfile -Command '(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory' | tr -d '\r')"
        elif [[ -r /proc/cpuinfo ]]; then
            echo "host_cpu: $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | xargs)"
        fi
        echo "postgres: $(psql_exp -c 'SHOW server_version')"
        # Docker Desktop's VM clock can drift from the host's (e.g. after sleep).
        # Every measurement uses PostgreSQL's clock, so this is recorded, not corrected.
        echo "clock_skew_seconds_db_minus_host: $(( $(psql_exp -c 'SELECT extract(epoch FROM now())::bigint') - $(date +%s) ))"
        echo "k6: $K6_IMAGE"
    } > "$out"
}

# run_k6 RESULTS_DIR SCRIPT [k6 options...]
# Runs k6 in the stack's Docker network, so requests go straight to http://api:8080.
# The script can write files to /results, which is RESULTS_DIR.
run_k6() {
    local results=$1 script=$2
    shift 2
    MSYS_NO_PATHCONV=1 docker run --rm \
        --network "${PROJECT}_default" \
        -v "$(host_path "$EXP_DIR/k6"):/scripts:ro" \
        -v "$(host_path "$results"):/results" \
        -e API_URL=http://api:8080 \
        "$@" \
        "$K6_IMAGE" run --quiet "/scripts/$script"
}

# Saves the throughput, statement statistics and history size of the last run
collect_db_metrics() {
    local results=$1
    psql_file "$EXP_DIR/sql/throughput.sql" > "$results/throughput.txt"
    psql_file "$EXP_DIR/sql/statements.sql" > "$results/statements.txt"
    psql_file "$EXP_DIR/sql/history-size.sql" > "$results/history-size.txt"
}
