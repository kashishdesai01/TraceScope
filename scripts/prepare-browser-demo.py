#!/usr/bin/env python3
"""Create real delayed-payment traces for browser checks, then clear the fault."""
import json
import os
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

payment = os.environ.get('PAYMENT_URL', 'http://localhost:8083')
gateway = os.environ.get('GATEWAY_URL', 'http://localhost:8081')
token = os.environ.get('CHAOS_TOKEN', 'local-chaos-token')

def fault(ms):
    body = json.dumps({'latencyMs': ms, 'errorRate': 0, 'dbSlowMs': 0, 'downstreamDown': ''}).encode()
    request = urllib.request.Request(payment + '/chaos', data=body, method='POST', headers={'Content-Type': 'application/json', 'X-Chaos-Token': token})
    with urllib.request.urlopen(request, timeout=15) as response:
        assert response.status == 200

def checkout(_):
    with urllib.request.urlopen(urllib.request.Request(gateway + '/api/checkout', data=b'', method='POST'), timeout=15) as response:
        assert response.status == 200

try:
    fault(800)
    with ThreadPoolExecutor(max_workers=5) as executor:
        list(executor.map(checkout, range(20)))
finally:
    fault(0)
ready = (int(time.time()) // 60 + 1) * 60 + 20
while time.time() < ready:
    time.sleep(min(3, ready - time.time()))
print('Twenty real delayed-payment traces prepared; fault cleared; measurement minute closed.')
