import http from 'k6/http';
import exec from 'k6/execution';
import crypto from 'k6/crypto';
import { Counter, Trend } from 'k6/metrics';
import { outcome, sameLogicalResult } from './contract.js';

const manifest = JSON.parse(open('/load/manifest.json'));
const profile = manifest.profiles[__ENV.BENCH_PROFILE || 'sanity'];
if (!profile) throw new Error('Unknown frozen workload profile');
const duration = `${profile.durationSeconds}s`;
const scenario = (fn, rate, unit, maximum) => ({
  executor: 'constant-arrival-rate', exec: fn, rate, timeUnit: `${unit}s`, duration,
  preAllocatedVUs: manifest.preAllocatedVUs, maxVUs: maximum, gracefulStop: `${manifest.gracefulStopSeconds}s`,
});
export const options = {
  scenarios: __ENV.BENCH_BOOTSTRAP === 'true' ? {
    seed: { executor: 'shared-iterations', exec: 'bootstrapOnly', vus: 1, iterations: 1, maxDuration: '60s' },
  } : {
    workflows: scenario('workflow', profile.workflowRate, profile.workflowTimeUnitSeconds, manifest.workflowMaxVUs),
    queries: scenario('query', profile.queryRate, profile.queryTimeUnitSeconds, manifest.queryMaxVUs),
  },
  insecureSkipTLSVerify: false,
  systemTags: ['method', 'status', 'scenario', 'name', 'expected_response'],
  summaryTrendStats: ['count', 'min', 'avg', 'med', 'max', 'p(95)', 'p(99)'],
  // No latency/throughput acceptance thresholds before owner review.
};
const classes = {};
for (const name of ['expected_success', 'expected_conflict', 'expected_accepted', 'expected_business_failure',
  'policy_limited', 'server_error', 'transport_error', 'contract_error']) {
  classes[name] = new Counter(`bench_${name}`);
}
const latencies = {};
for (const name of ['api', 'create', 'confirm', 'refund', 'query', 'replay', 'conflict']) {
  latencies[name] = new Trend(`bench_${name}_ms`, true);
}
const started = new Counter('bench_started_workflows');
const completed = new Counter('bench_completed_workflows');
const queried = new Counter('bench_completed_queries');
const queryStarted = new Counter('bench_started_queries');
const requests = new Counter('bench_requests');
const responseCallback = http.expectedStatuses(200, 201, 202, 409, 429);

function raw(path, method, body, headers, name) {
  return http.request(method, `https://edge:8443/api/v1${path}`, body === null ? null : JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json', ...headers }, tags: { name },
    timeout: `${manifest.requestTimeoutSeconds}s`, redirects: 0, responseCallback,
  });
}
function parse(response) {
  try { return response.json(); } catch (_) { return {}; }
}
function control(body = null) {
  const response = http.request(body === null ? 'GET' : 'POST', 'http://receiver:8081/',
    body === null ? null : JSON.stringify(body), {
      headers: { 'Content-Type': 'application/json' }, tags: { name: 'fixture_control' }, timeout: '5s',
    });
  if (response.status !== (body === null ? 200 : 204)) throw new Error('Owned receiver control failed');
  return body === null ? response.json() : null;
}
function bootstrap(path, body, headers, expected) {
  const response = raw(path, 'POST', body, headers, 'fixture_setup');
  const envelope = parse(response);
  if (response.status !== expected || !envelope.data) throw new Error(`owned fixture status expected ${expected}, got ${response.status}`);
  return envelope.data;
}
export function setup() {
  const existing = control().client;
  if (existing) return existing;
  const hex = (bytes) => Array.from(new Uint8Array(bytes), (value) => value.toString(16).padStart(2, '0')).join('');
  const suffix = hex(crypto.randomBytes(12));
  const email = `load-${suffix}@example.invalid`;
  const password = hex(crypto.randomBytes(24));
  const marker = { 'X-FlowPay-CSRF': '1' };
  bootstrap('/auth/register', { email, password, firstName: 'Load', lastName: 'Fixture', merchantName: 'Owned load fixture' }, marker, 201);
  const jwt = bootstrap('/auth/login', { email, password }, marker, 200).accessToken;
  const dashboard = { Authorization: `Bearer ${jwt}` };
  const key = bootstrap('/merchant/api-keys', { name: 'Owned load fixture' }, dashboard, 201).key;
  for (const path of ['/fast', '/slow', '/flaky']) {
    const endpoint = bootstrap('/merchant/webhook-endpoints', {
      url: `https://11.203.0.10:8443${path}`,
      events: ['payment.processing', 'payment.succeeded', 'payment.failed', 'refund.processing', 'refund.succeeded', 'refund.failed'],
    }, dashboard, 201);
    control({ keys: { [path]: endpoint.secret } });
  }
  const data = { key, suffix };
  control({ client: data }); // Disposable receiver RAM only, never summary/artifacts.
  return data;
}
function call(path, method, body, headers, operation, expected) {
  const start = Date.now();
  const response = raw(path, method, body, headers, operation);
  const elapsed = Date.now() - start;
  const envelope = parse(response);
  const category = outcome(response.status, expected, envelope.data, envelope.code);
  classes[category].add(1);
  requests.add(1);
  latencies.api.add(elapsed);
  latencies[operation].add(elapsed);
  return category === 'expected_success' || category === 'expected_conflict' ? envelope : null;
}
function invariant(value) {
  if (!value) { classes.contract_error.add(1); return false; }
  return true;
}
export function workflow(data) {
  started.add(1);
  const unique = `${data.suffix}-${exec.vu.idInTest}-${exec.scenario.iterationInTest}-${__ENV.BENCH_BOOTSTRAP === 'true' ? 'setup' : __ENV.BENCH_PROFILE}`;
  const auth = { Authorization: `Bearer ${data.key}` };
  const create = { ...auth, 'Idempotency-Key': `load-create-${unique}` };
  const body = { amount: manifest.workflow.amountMinor, currency: manifest.workflow.currency };
  const first = call('/payment-intents', 'POST', body, create, 'create', 201);
  if (!first) return;
  const replay = call('/payment-intents', 'POST', body, create, 'replay', 201);
  if (!replay || !invariant(sameLogicalResult(first.data, replay.data))) return;
  if (!call('/payment-intents', 'POST', { ...body, amount: body.amount + 1 }, create, 'conflict', 409)) return;
  const payment = `/payment-intents/${first.data.id}`;
  const confirmHeaders = { ...auth, 'Idempotency-Key': `load-confirm-${unique}` };
  const confirm = call(`${payment}/confirm`, 'POST', null, confirmHeaders, 'confirm', 200);
  if (!confirm || !invariant(confirm.data.paymentStatus === 'SUCCEEDED')) return;
  const confirmReplay = call(`${payment}/confirm`, 'POST', null, confirmHeaders, 'replay', 200);
  if (!confirmReplay || !invariant(sameLogicalResult(confirm.data, confirmReplay.data))) return;
  const refundHeaders = { ...auth, 'Idempotency-Key': `load-refund-${unique}` };
  const refund = call(`${payment}/refunds`, 'POST', { amount: manifest.workflow.refundMinor }, refundHeaders, 'refund', 201);
  if (!refund || !invariant(refund.data.status === 'SUCCEEDED')) return;
  const refundReplay = call(`${payment}/refunds`, 'POST', { amount: manifest.workflow.refundMinor }, refundHeaders, 'replay', 201);
  if (!refundReplay || !invariant(sameLogicalResult(refund.data, refundReplay.data))) return;
  const view = call(payment, 'GET', null, auth, 'query', 200);
  if (!view || !invariant(view.data.refundedAmount === manifest.workflow.refundMinor && view.data.status === 'PARTIALLY_REFUNDED')) return;
  const list = call('/payment-intents?page=0&size=20', 'GET', null, auth, 'query', 200);
  if (!list || !invariant(Array.isArray(list.data) && list.meta && list.meta.page === 0)) return;
  completed.add(1);
}
export function query(data) {
  queryStarted.add(1);
  const result = call('/payment-intents?page=0&size=20', 'GET', null, { Authorization: `Bearer ${data.key}` }, 'query', 200);
  if (result && invariant(Array.isArray(result.data) && result.meta && result.meta.size === 20)) queried.add(1);
}
export function bootstrapOnly(data) { workflow(data); }
export function handleSummary(data) {
  const allowed = ['dropped_iterations', 'iterations', 'vus', 'vus_max'];
  const safe = {};
  for (const [name, metric] of Object.entries(data.metrics)) {
    if (!name.startsWith('bench_') && !allowed.includes(name)) continue;
    const values = {};
    for (const [key, value] of Object.entries(metric.values)) {
      if (typeof value === 'number' && Number.isFinite(value)) values[key] = value;
    }
    safe[name] = values;
  }
  return { stdout: `FLOWPAY_SAFE_SUMMARY ${JSON.stringify({ metrics: safe })}\n` };
}
