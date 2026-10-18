import { outcome, sameLogicalResult } from './contract.js';
import { Counter } from 'k6/metrics';
const schemaCounter = new Counter('schema_counter');
export const options = { vus: 1, iterations: 1 };
export default function () {
  schemaCounter.add(1);
  const cases = [
    [201, 201, { id: 'pi_fixture' }, null, 'expected_success'],
    [409, 409, null, 'IDEMPOTENCY_KEY_REUSED', 'expected_conflict'],
    [409, 201, null, 'IDEMPOTENCY_KEY_REUSED', 'contract_error'],
    [202, 200, { paymentStatus: 'PROCESSING' }, null, 'contract_error'],
    [202, 202, { paymentStatus: 'PROCESSING' }, null, 'expected_accepted'],
    [202, 202, { status: 'PROCESSING' }, null, 'expected_accepted'],
    [200, 200, { paymentStatus: 'FAILED' }, null, 'contract_error'],
    [429, 201, null, 'RATE_LIMIT_EXCEEDED', 'policy_limited'],
    [429, 201, null, 'OTHER', 'contract_error'],
    [500, 200, null, null, 'server_error'],
    [0, 201, null, null, 'transport_error'],
    [200, 200, null, null, 'contract_error'],
  ];
  for (const [status, expected, data, code, category] of cases) {
    if (outcome(status, expected, data, code) !== category) throw new Error('Fixed status-classification contract failed');
  }
  if (outcome(200, 200, { paymentStatus: 'FAILED' }, null, 'DECLINED') !== 'expected_business_failure') throw new Error('Expected decline classification failed');
  if (outcome(201, 201, { status: 'FAILED' }, null, 'DECLINED') !== 'expected_business_failure') throw new Error('Expected refund decline classification failed');
  if (!sameLogicalResult({ a: 1, b: [2, null] }, { b: [2, null], a: 1 })) throw new Error('Logical comparison failed');
  if (sameLogicalResult({ a: 1 }, { a: 2 })) throw new Error('Changed replay was accepted');
}
export function handleSummary(data) {
  if (data.metrics.schema_counter.values.count !== 1) throw new Error('Fixed metric schema check failed');
  return { stdout: 'PASS: native k6 contract controls and metric schema\n' };
}
