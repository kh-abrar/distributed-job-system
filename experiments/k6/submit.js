// E1: submission latency at a fixed arrival rate.
//
// "constant-arrival-rate" starts RATE requests every second whether or not earlier
// ones have answered. A closed loop (send, wait, send) would slow down along with
// the server and hide exactly the latency we want to see.
//
// Settings (environment variables):
//   API_URL   base URL of the api (set by lib.sh to http://api:8080)
//   RATE      requests per second during the measured phase (default 200)
//   DURATION  length of the measured phase (default 2m)
//   WARMUP    length of the warm-up phase, at the same rate, not measured (default 30s)

import http from 'k6/http';
import { check } from 'k6';

const API_URL = __ENV.API_URL || 'http://localhost:8081';
const RATE = Number(__ENV.RATE || 200);
const DURATION = __ENV.DURATION || '2m';
const WARMUP = __ENV.WARMUP || '30s';

export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: WARMUP,
            preAllocatedVUs: 50,
            maxVUs: 500,
            tags: { phase: 'warmup' },
        },
        measured: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            startTime: WARMUP,
            preAllocatedVUs: 50,
            maxVUs: 500,
            tags: { phase: 'measured' },
        },
    },
    // Sub-metrics for the measured phase only, so warm-up doesn't count
    thresholds: {
        'http_req_duration{phase:measured}': [],
        'http_req_failed{phase:measured}': [],
        'http_reqs{phase:measured}': [],
        'dropped_iterations': [],
    },
    summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const body = JSON.stringify({ type: 'IO', payload: { sleepMs: 0 } });
const params = { headers: { 'Content-Type': 'application/json' } };

export default function () {
    const response = http.post(`${API_URL}/api/jobs`, body, params);
    check(response, { 'status is 201': (r) => r.status === 201 });
}

// Writes the summary to /results (the run's results directory) as well as the console
export function handleSummary(data) {
    const measured = data.metrics['http_req_duration{phase:measured}'];
    const failed = data.metrics['http_req_failed{phase:measured}'];
    const requests = data.metrics['http_reqs{phase:measured}'];
    const dropped = data.metrics['dropped_iterations'];
    // An iteration contains its request, so its duration bounds the request's from above.
    // Request timings can absorb a clock step in Docker's VM; this one is a sanity check.
    const iterations = data.metrics['iteration_duration'];
    const lines = [
        `rate_per_second: ${RATE}`,
        `measured_duration: ${DURATION}`,
        `warmup: ${WARMUP}`,
        `requests_measured: ${requests ? requests.values.count : 'n/a'}`,
        `latency_ms_p50: ${measured ? measured.values.med.toFixed(2) : 'n/a'}`,
        `latency_ms_p95: ${measured ? measured.values['p(95)'].toFixed(2) : 'n/a'}`,
        `latency_ms_p99: ${measured ? measured.values['p(99)'].toFixed(2) : 'n/a'}`,
        `latency_ms_max: ${measured ? measured.values.max.toFixed(2) : 'n/a'}`,
        `iteration_ms_max: ${iterations ? iterations.values.max.toFixed(2) : 'n/a'}`,
        `error_rate: ${failed ? failed.values.rate : 'n/a'}`,
        `dropped_iterations: ${dropped ? dropped.values.count : 0}`,
    ];
    return {
        '/results/k6-summary.json': JSON.stringify(data, null, 2),
        '/results/latency.txt': lines.join('\n') + '\n',
        stdout: lines.join('\n') + '\n',
    };
}
