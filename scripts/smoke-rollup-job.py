#!/usr/bin/env python3
"""Loopback-only synthetic verification: VM → durable job → worker → CH → heatmap."""
import base64
import datetime as dt
import json
import os
from pathlib import Path
import time
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
API = os.environ.get('NOERIVA_SMOKE_API', 'http://127.0.0.1:18080')
VM = os.environ.get('NOERIVA_METRICS_URL', 'http://127.0.0.1:18428')
for target in (API, VM):
    if urllib.parse.urlsplit(target).hostname not in ('127.0.0.1', 'localhost', '::1'):
        raise SystemExit('Synthetic verification only accepts loopback targets')
env = dict(line.split('=', 1) for line in (ROOT / '.env').read_text().splitlines()
           if line and not line.startswith('#'))

def call(base, path, auth=None, body=None):
    headers = {'Content-Type': 'application/json', 'X-Noeriva-Request': '1'}
    if auth:
        headers['Authorization'] = auth
    request = urllib.request.Request(base + path, headers=headers,
        data=None if body is None else json.dumps(body).encode())
    with urllib.request.urlopen(request, timeout=15) as response:
        raw = response.read()
        return json.loads(raw) if raw else None

basic = 'Basic ' + base64.b64encode(('admin:' + env['NOERIVA_BOOTSTRAP_PASSWORD']).encode()).decode()
auth = 'Bearer ' + call(API, '/api/v1/session', basic)['accessToken']
run_id = uuid.uuid4().hex[:12]
device = call(API, '/api/v1/devices', auth, {
    'name': 'synthetic-rollup-' + run_id, 'type': 'ROUTER', 'siteId': 'default',
    'vendor': 'Simulator', 'model': 'Synthetic-only', 'managementAddress': '192.0.2.12'})
interface = call(API, f"/api/v1/devices/{device['id']}/interfaces", auth,
                 {'name': 'synthetic0', 'speedBps': '1000000000', 'macAddress': '02:00:00:00:00:12'})
# Three-hour-old closed hour proves historical repair beyond the recent sweep.
start = int(time.time()) // 3600 * 3600 - 3 * 3600
end = start + 3600
metric = {'__name__': 'noeriva_interface_receive_bytes_total', 'organization_id': 'default',
          'device_id': device['id'], 'interface_id': interface['id'],
          'source_id': 'primary', 'source_epoch': 'synthetic-' + run_id}
samples = {'metric': metric, 'values': [1_000_000 + seconds * 100 for seconds in range(0, 3601, 15)],
           'timestamps': [(start + seconds) * 1000 for seconds in range(0, 3601, 15)]}
call(VM, '/api/v1/import', body=samples)
# VM import acknowledgement is distinct from raw visibility; bounded polling.
selector = 'noeriva_interface_receive_bytes_total{device_id="' + device['id'] + '"}'
query = urllib.parse.urlencode({'match[]': selector, 'start': start, 'end': end})
deadline = time.monotonic() + 45
while True:
    visible = call(VM, '/api/v1/export?' + query)
    if visible and len(visible.get('values', [])) == 241:
        break
    if time.monotonic() >= deadline:
        raise AssertionError('Imported synthetic raw counter did not become visible within 45 seconds')
    time.sleep(1)

def iso(value):
    return dt.datetime.fromtimestamp(value, dt.timezone.utc).isoformat().replace('+00:00', 'Z')

job = call(API, '/api/v1/query-jobs', auth,
    {'deviceId': device['id'], 'interfaceId': interface['id'],
     'from': iso(start), 'to': iso(end), 'direction': 'rx'})
assert job['status'] == 'PENDING', job
deadline = time.monotonic() + 70
while job['status'] in ('PENDING', 'RUNNING'):
    if time.monotonic() >= deadline:
        raise AssertionError('Durable repair did not finish within 70 seconds')
    time.sleep(1)
    job = call(API, '/api/v1/query-jobs/' + job['id'], auth)
assert job['status'] == 'SUCCEEDED' and job['bucketCount'] == 12, job
heatmap = call(API, f"/api/v1/devices/{device['id']}/interfaces/{interface['id']}/bandwidth/heatmap?timezone=UTC", auth)
observed = [cell for cell in heatmap['cells'] if cell['value'] is not None]
assert len(observed) == 1 and abs(observed[0]['value'] - 800) < 0.001, observed
result = {'status': 'PASS', 'mode': 'CONNECTED_COMPOSE', 'synthetic': True,
          'runId': run_id, 'deviceId': device['id'], 'interfaceId': interface['id'],
          'jobId': job['id'], 'jobStatus': job['status'], 'bucketCount': job['bucketCount'],
          'from': iso(start), 'to': iso(end), 'observedCells': len(observed),
          'averageBps': observed[0]['value'],
          'checks': ['actual VM import/export', 'durable administrative job',
                     'independent container worker', '12 replacement ClickHouse buckets',
                     'historical one-hour heatmap value 800 bps', '167 other cells remain missing/future']}
(ROOT / 'docs/implementation/rollup-job-smoke.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
