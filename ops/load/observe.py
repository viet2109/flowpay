"""Private Prometheus/receiver observer. Fixed scalar/numeric projections only."""
import json
import math
import sys
import time
import urllib.parse
import urllib.request

GAUGES = {
    'heapBytes': 'sum(jvm_memory_used_bytes{job="flowpay",area="heap"})',
    'heapRatio': 'sum(jvm_memory_used_bytes{job="flowpay",area="heap"}) / sum(jvm_memory_max_bytes{job="flowpay",area="heap"})',
    'cpuRatio': 'max(process_cpu_usage{job="flowpay"})',
    'poolActive': 'sum(hikaricp_connections_active{job="flowpay"})',
    'poolPending': 'sum(hikaricp_connections_pending{job="flowpay"})',
    'poolMax': 'sum(hikaricp_connections_max{job="flowpay"})',
    'gcPauseRateSeconds': 'sum(rate(jvm_gc_pause_seconds_sum{job="flowpay"}[1m]))',
    'outboxBacklog': 'sum(flowpay:outbox_backlog_rows)',
    'outboxOldestSeconds': 'flowpay:outbox_oldest_age_seconds',
    'webhookBacklog': 'sum(flowpay:webhook_backlog_rows{status!="DEAD"})',
    'webhookOverdueSeconds': 'flowpay:webhook_oldest_due_age_seconds',
    'outboxSampleAgeSeconds': 'max(flowpay_metrics_sample_age_seconds{job="flowpay",component="OUTBOX"})',
    'webhookSampleAgeSeconds': 'max(flowpay_metrics_sample_age_seconds{job="flowpay",component="WEBHOOK"})',
    'scrapeUp': 'min(up{job="flowpay"})',
}


def http(url, body=None):
    request = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=10) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


def query(expression):
    return http('http://prometheus:9090/api/v1/query?' + urllib.parse.urlencode({'query': expression}))['data']['result']


def scalar(expression):
    values = query(expression)
    if len(values) != 1:
        raise ValueError('Required scalar telemetry missing')
    value = float(values[0]['value'][1])
    if not math.isfinite(value):
        raise ValueError('Non-finite telemetry')
    return value


def histogram_snapshot(after):
    result = {}
    deadline = time.monotonic() + 60
    for provider in ('payment', 'refund'):
        name = 'flowpay_' + provider + '_provider_duration_seconds_bucket'
        while scalar('min(timestamp(' + name + '{job="flowpay",outcome="SUCCESS"}))') < after:
            if time.monotonic() >= deadline:
                raise ValueError('Provider scrape freshness deadline')
            time.sleep(2)
        buckets = query('sum by (le) (' + name + '{job="flowpay",outcome="SUCCESS"})')
        result[provider] = {row['metric']['le']: float(row['value'][1]) for row in buckets}
        if not result[provider] or '+Inf' not in result[provider]:
            raise ValueError('Provider buckets incomplete')
    return result


def ranges(start, end):
    result = {}
    for name, expression in GAUGES.items():
        data = http('http://prometheus:9090/api/v1/query_range?' + urllib.parse.urlencode(
            {'query': expression, 'start': start, 'end': end, 'step': 15}))['data']['result']
        if len(data) != 1:
            raise ValueError('Required range telemetry missing')
        values = [float(row[1]) for row in data[0]['values']]
        if not values or not all(math.isfinite(value) for value in values):
            raise ValueError('Missing/non-finite range observations')
        third = max(1, len(values) // 3)
        result[name] = {'samples': len(values), 'min': min(values), 'max': max(values),
                        'mean': sum(values) / len(values), 'firstThirdMean': sum(values[:third]) / third,
                        'lastThirdMean': sum(values[-third:]) / third}
    return result


def main():
    command = sys.argv[1]
    if command == 'phase':
        http('http://receiver:8081/', {'phase': sys.argv[2]})
        return {'phaseChanged': True}
    if command == 'receiver':
        return http('http://receiver:8081/stats')
    if command == 'histograms':
        return histogram_snapshot(float(sys.argv[2]))
    if command == 'ranges':
        return ranges(float(sys.argv[2]), float(sys.argv[3]))
    raise ValueError('Unknown observer command')


if __name__ == '__main__':
    try:
        print(json.dumps(main(), allow_nan=False))
    except Exception:
        print('FAIL: owned benchmark observer missing/failing telemetry; raw diagnostics withheld')
        sys.exit(1)
