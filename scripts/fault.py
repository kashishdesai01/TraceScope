#!/usr/bin/env python3
"""Change faults only in the local synthetic demo services."""
import argparse
import json
import os
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('service', choices=['gateway', 'checkout', 'payment', 'inventory'])
parser.add_argument('--latency-ms', type=int, default=0)
parser.add_argument('--error-rate', type=float, default=0)
parser.add_argument('--db-slow-ms', type=int, default=0)
parser.add_argument('--downstream-down', choices=['', 'payment', 'inventory'], default='')
args = parser.parse_args()
ports = {'gateway': 8081, 'checkout': 8082, 'payment': 8083, 'inventory': 8084}
url = os.environ.get(args.service.upper() + '_URL', f'http://localhost:{ports[args.service]}')
body = json.dumps({'latencyMs': args.latency_ms, 'errorRate': args.error_rate,
                   'dbSlowMs': args.db_slow_ms, 'downstreamDown': args.downstream_down}).encode()
request = urllib.request.Request(url + '/chaos', data=body, method='POST', headers={
    'Content-Type': 'application/json', 'X-Chaos-Token': os.environ.get('CHAOS_TOKEN', 'local-chaos-token')})
with urllib.request.urlopen(request, timeout=15) as response:
    print(json.dumps(json.load(response), indent=2))
