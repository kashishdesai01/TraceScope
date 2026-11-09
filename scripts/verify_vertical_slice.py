#!/usr/bin/env python3
"""Verify a recorded checkout-only workload through the public API after its minute closes."""
import argparse
import json
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--url', default='http://localhost:8080')
parser.add_argument('--traffic', default='reports/runtime/vertical-traffic.json')
args = parser.parse_args()
traffic = json.loads(Path(args.traffic).read_text())

def get(path):
    with urllib.request.urlopen(args.url + '/api/v1' + path, timeout=20) as response:
        return json.load(response)

# Closed-minute metrics intentionally lag raw traces. Wait for the workload's last minute.
closed_at = (int(traffic['end']) // 60 + 1) * 60
while time.time() < closed_at:
    time.sleep(min(3, closed_at - time.time()))
services = get('/services')
service_map = get('/service-map')
expected = {('gateway', 'checkout'), ('checkout', 'payment'), ('checkout', 'inventory'),
            ('payment', 'postgresql:payments'), ('inventory', 'postgresql:inventory')}
actual = {(edge['caller'], edge['callee']) for edge in service_map['edges']}
assert actual == expected, (actual, expected)
gateway = next(service for service in services['data'] if service['service'] == 'gateway')
assert gateway['requests'] == traffic['requests'], gateway
traces = get('/traces?service=gateway&limit=100')
checkout = next(trace for trace in traces['data'] if trace['root_name'] == 'POST /api/checkout')
trace = get('/traces/' + checkout['trace_id'])
assert len(trace['tree']['nodes']) == 9
assert {node['span']['service'] for node in trace['tree']['nodes']} == {'gateway', 'checkout', 'payment', 'inventory'}
assert sum(segment['durationNs'] for segment in trace['criticalPath']['segments']) == trace['criticalPath']['durationNs']
output = {'verifiedAt': datetime.now(timezone.utc).isoformat(), 'services': services,
          'serviceMap': service_map, 'trace': trace, 'traffic': traffic}
Path('reports/runtime').mkdir(parents=True, exist_ok=True)
Path('reports/runtime/vertical-api.json').write_text(json.dumps(output, indent=2))
print(json.dumps({'result': 'PASS', 'requests': gateway['requests'], 'edges': len(actual),
                  'traceSpans': len(trace['tree']['nodes']), 'traceId': checkout['trace_id']}))
