#!/usr/bin/env python3
"""Read-only acceptance of explicitly selected synthetic devices in the local COMPACT deployment."""
import argparse, base64, concurrent.futures, datetime, json, statistics, time, urllib.parse, urllib.request
from pathlib import Path

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--snmp-device', required=True)
p.add_argument('--redfish-device', required=True)
a = p.parse_args()
for value in (a.snmp_device, a.redfish_device):
    assert all(c.isalnum() or c in '_-' for c in value) and len(value) <= 64
values = dict(line.split('=', 1) for line in (root / '.env').read_text().splitlines() if line and not line.startswith('#') and '=' in line)
base = 'http://127.0.0.1:18080/api/v1'
# This verifier targets only the local stack; never forward its credentials through environment proxies.
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
password = values['NOERIVA_BOOTSTRAP_PASSWORD'].strip().strip('\"\'')
headers = {'Authorization': 'Basic ' + base64.b64encode(('admin:' + password).encode()).decode()}
def get(path, request_headers=None):
    started = time.perf_counter()
    with opener.open(urllib.request.Request(base + path, headers=request_headers or headers), timeout=20) as response:
        raw = response.read().decode()
    for secret in (password, values.get('NOERIVA_CREDENTIAL_KEY', ''), 'synthetic-snmp-auth-2026', 'synthetic-snmp-priv-2026', 'synthetic-redfish-cua-2026'):
        assert not secret or secret not in raw, 'credential appeared in an API response'
    return json.loads(raw), (time.perf_counter()-started)*1000
# Session has a token intentionally; never include its response in the report.
with opener.open(urllib.request.Request(base+'/session', headers=headers), timeout=10) as response:
    token = json.load(response)['accessToken']
headers = {'Authorization': 'Bearer ' + token}
report = {'observedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'provenance': 'SYNTHETIC_PROTOCOL_FIXTURES', 'realHardwareTested': False, 'devices': []}
for device, slot, expected in ((a.snmp_device, 'snmp', {'cpu_percent':17, 'memory_percent':42, 'temperature_celsius':36}), (a.redfish_device, 'redfish', {'power_watts':180, 'temperature_celsius':23.5})):
    prefix = '/devices/'+device
    management, _ = get(prefix+'/management')
    assert management['device']['name'].startswith('Mock-'), 'This script only accepts named Mock devices'
    connections, _ = get(prefix+'/connections')
    connection = next(v for v in connections['items'] if v['slot'] == slot)
    assert connection['lastReading'] is not None and connection['status'] in ('SUCCESS', 'PARTIAL', 'RUNNING', 'DISABLED')
    assert all(key not in connection for key in ('ciphertext','secrets','password','authPassword','privacyPassword','community'))
    collection, _ = get(prefix+'/collection')
    assert all(key not in item for item in collection['items'] for key in ('host','username','certificateSha256','ciphertext','secrets'))
    summary, _ = get(prefix+'/summary')
    assert any(s['sourceId'] == ('network' if slot == 'snmp' else 'bmc') for s in summary['sources'])
    series_results=[]
    for metric, expected_value in expected.items():
        series, _ = get(prefix+'/metrics?'+urllib.parse.urlencode({'metric':metric,'points':120}))
        assert series['points'] and all(point['value'] == expected_value for point in series['points'] if point['value'] is not None), metric
        series_results.append({'metric':metric,'points':len(series['points']),'latest':series['points'][-1]['value'],'source':series['source']})
    interfaces, _ = get(prefix+'/interfaces')
    heatmap_result=None
    if slot == 'snmp':
        assert interfaces['items'], 'SNMP must populate the interface catalog'
        interface=interfaces['items'][0]
        heatmap,_=get(prefix+'/interfaces/'+interface['id']+'/bandwidth/heatmap?timezone=Asia%2FShanghai')
        assert len(heatmap['cells']) == 168
        heatmap_result={'interfaceId':interface['id'],'cells':len(heatmap['cells']),'source':heatmap.get('source'),'qualityFlags':heatmap.get('qualityFlags')}
        assert 'network' in str(heatmap.get('source')), 'wrong rollup source'
    report['devices'].append({'id':device,'name':management['device']['name'],'slot':slot,'connectionRevision':connection['revision'],'inventoryRevision':management['inventoryRevision'],'status':connection['status'],'observedAt':connection['lastReading']['observedAt'],'identity':connection['lastReading']['identity'],'qualityFlags':connection['lastReading']['qualityFlags'],'series':series_results,'heatmap':heatmap_result})
# Measure bounded state queries while the background worker continues to collect.
path='/devices/'+a.snmp_device+'/collection'
with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
    timings=list(pool.map(lambda _:get(path)[1], range(160)))
timings.sort()
report['stateReadLoad']={'requests':len(timings),'concurrency':16,'errors':0,'medianMs':round(statistics.median(timings),2),'p95Ms':round(timings[int(len(timings)*.95)-1],2),'maxMs':round(max(timings),2),'scope':'Local connected stack; not production capacity certification'}
collector,_=get('/collectors')
report['worker']=next(v for v in collector['items'] if v['id']=='device-poller')
output=root/'docs/implementation/device-runtime-verification.json'
output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'report':str(output),'devices':len(report['devices']),'stateReadLoad':report['stateReadLoad']},ensure_ascii=False))
