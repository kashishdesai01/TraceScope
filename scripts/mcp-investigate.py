#!/usr/bin/env python3
"""Record a read-only MCP investigation against a local synthetic demo."""
import argparse
import datetime
import json
import os
from pathlib import Path
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('kind', choices=['latency', 'errors'])
parser.add_argument('--url', default=os.getenv('TRACESCOPE_URL', 'http://localhost:8080'))
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
headers = {'Authorization': 'Bearer ' + os.getenv('MCP_TOKEN', 'local-mcp-token'),
           'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream'}
sequence = 0
record = {'startedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'investigator': 'Codex via local MCP JSON-RPC; no external Claude invocation', 'calls': []}

def rpc(method, params, notification=False):
    global sequence
    sequence += 1
    body = {'jsonrpc': '2.0', 'method': method, 'params': params}
    if not notification:
        body['id'] = sequence
    request = urllib.request.Request(args.url.rstrip('/') + '/mcp', json.dumps(body).encode(), headers)
    with urllib.request.urlopen(request, timeout=30) as response:
        if response.headers.get('Mcp-Session-Id'):
            headers['Mcp-Session-Id'] = response.headers['Mcp-Session-Id']
        raw = response.read().decode()
    if not raw:
        return {}
    if any(line.startswith('data:') for line in raw.splitlines()):
        raw = '\n'.join(line[5:].strip() for line in raw.splitlines() if line.startswith('data:'))
    result = json.loads(raw)
    if 'error' in result:
        raise RuntimeError(result['error'])
    return result.get('result', {})

def tool(name, **arguments):
    result = rpc('tools/call', {'name': name, 'arguments': arguments})
    if result.get('isError'):
        raise RuntimeError(result)
    value = json.loads(result['content'][0]['text'])
    record['calls'].append({'tool': name, 'arguments': arguments, 'result': value})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(record, indent=2))
    return value

record['initialize'] = rpc('initialize', {'protocolVersion': '2025-03-26', 'capabilities': {},
    'clientInfo': {'name': 'codex-investigation', 'version': '1.0'}})
rpc('notifications/initialized', {}, notification=True)
record['availableTools'] = [t['name'] for t in rpc('tools/list', {})['tools']]
tool('list_services', minutes=15)
tool('get_service_health', service='checkout', minutes=3, compareMinutes=3)
tool('get_service_dependencies', service='checkout', minutes=3)
traces = tool('find_slow_traces' if args.kind == 'latency' else 'find_error_traces',
              service='checkout', minutes=5, limit=3)
for trace in traces['traces']:
    tool('get_trace_summary', traceId=trace['trace_id'])
tool('list_alerts', state='open')
print(json.dumps({'saved': str(args.output), 'calls': len(record['calls']), 'traces': len(traces['traces'])}))
