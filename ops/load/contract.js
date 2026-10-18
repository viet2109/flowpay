// Pure protocol helpers; no credentials, remote imports or dynamic metric tags.
export function outcome(status, expected, data, problemCode, providerOutcome = 'SUCCESS') {
  if (status === 0) return 'transport_error';
  if (status >= 500) return 'server_error';
  if (status === 429) return problemCode === 'RATE_LIMIT_EXCEEDED' ? 'policy_limited' : 'contract_error';
  if (status !== expected) return 'contract_error';
  if (expected === 409) return problemCode === 'IDEMPOTENCY_KEY_REUSED' ? 'expected_conflict' : 'contract_error';
  if (expected === 202) return data && (data.paymentStatus === 'PROCESSING' || data.status === 'PROCESSING') ? 'expected_accepted' : 'contract_error';
  if (data && (data.paymentStatus === 'FAILED' || data.status === 'FAILED')) {
    return providerOutcome === 'DECLINED' ? 'expected_business_failure' : 'contract_error';
  }
  return data !== undefined && data !== null ? 'expected_success' : 'contract_error';
}

export function sameLogicalResult(left, right) {
  if (left === null || right === null || typeof left !== typeof right) return left === right;
  if (typeof left !== 'object') return left === right;
  const keys = Object.keys(left).sort();
  return JSON.stringify(keys) === JSON.stringify(Object.keys(right).sort()) &&
    keys.every((key) => sameLogicalResult(left[key], right[key]));
}
