#!/usr/bin/env python3
"""Read-only verification of explicitly selected assets in the local NOERIVA stack.

The input JSON is a task-local list of {id, host, slot}; this script never connects
to the managed hosts or changes their connection settings.
"""
import argparse
import base64
import datetime as dt
import json
from pathlib import Path
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--assets', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--max-age-seconds', type=int, default=180)
    args = parser.parse_args()
    assets = json.loads(args.assets.read_text())
    assert 1 <= len(assets) <= 16 and 15 <= args.max_age_seconds <= 3600
    assert len({x['id'] for x in assets}) == len(assets)
    values = dict(line.split('=', 1) for line in (ROOT / '.env').read_text().splitlines()
                  if line and not line.startswith('#') and '=' in line)
    password = values['NOERIVA_BOOTSTRAP_PASSWORD'].strip().strip('\"\'')
    headers = {'Authorization': 'Basic ' + base64.b64encode(('admin:' + password).encode()).decode()}
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def get(path, session=False):
        request = urllib.request.Request('http://127.0.0.1:18080/api/v1' + path, headers=headers)
        with opener.open(request, timeout=20) as response:
            raw = response.read().decode()
        assert password not in raw
        assert not values.get('NOERIVA_CREDENTIAL_KEY') or values['NOERIVA_CREDENTIAL_KEY'] not in raw
        result = json.loads(raw)
        if not session:
            def inspect(value):
                if isinstance(value, dict):
                    assert not {'password', 'authPassword', 'privacyPassword', 'community', 'ciphertext', 'secrets', 'accessToken'} & value.keys()
                    for child in value.values():
                        inspect(child)
                elif isinstance(value, list):
                    for child in value:
                        inspect(child)
            inspect(result)
        return result

    headers['Authorization'] = 'Bearer ' + get('/session', session=True)['accessToken']
    now = dt.datetime.now(dt.timezone.utc)
    report = {'observedAt': now.isoformat(), 'scope': 'Selected real hardware via the local platform API',
              'method': 'READ_ONLY_API_CHECK', 'devices': []}
    for item in assets:
        identifier = item['id']
        assert isinstance(identifier, str) and 1 <= len(identifier) <= 64 and all(c.isalnum() or c in '_-' for c in identifier)
        prefix = '/devices/' + identifier
        management = get(prefix + '/management')
        device = management['device']
        assert device['managementAddress'] == item['host']
        matches = get('/devices?' + urllib.parse.urlencode({'q': item['host'], 'siteId': device['siteId'], 'limit': 100}))
        assert sum(x['managementAddress'] == item['host'] for x in matches['items']) == 1, 'Duplicate selected management address'
        connection = next(x for x in get(prefix + '/connections')['items'] if x['slot'] == item['slot'])
        assert connection['enabled'] and not connection.get('errorCode')
        if item['slot'] in ('ssh', 'redfish'):
            assert connection['hasPassword']
        reading = connection['lastReading']
        observed = dt.datetime.fromisoformat(reading['observedAt'].replace('Z', '+00:00'))
        age = (dt.datetime.now(dt.timezone.utc) - observed).total_seconds()
        assert -5 <= age <= args.max_age_seconds, (device['name'], 'stale observation', round(age))
        identity = reading['identity']
        assert identity['model'] and identity['firmware'], 'Hardware identity must come from a successful protocol reading'
        for key, expected in item.get('expectedIdentity', {}).items():
            assert identity.get(key) == expected, (device['name'], key, 'identity mismatch')
        safe = get(prefix + '/collection')
        assert all(not {'host', 'username', 'sshHostKeySha256', 'certificateSha256'} & x.keys() for x in safe['items'])
        summary = get(prefix + '/summary')
        source = {'ssh': 'ssh', 'redfish': 'bmc', 'snmp': 'network'}[item['slot']]
        assert any(x['sourceId'] == source for x in summary['sources']), 'Reading was not published'
        series = []
        for metric in reading.get('facts', {}).get('publishedMetricIds', '').replace('[', '').replace(']', '').replace('"', '').split(','):
            metric = metric.strip()
            if metric not in ('temperature_celsius', 'power_watts', 'cpu_percent', 'memory_percent'):
                continue
            result = get(prefix + '/metrics?' + urllib.parse.urlencode({'metric': metric, 'points': 120}))
            present = [p for p in result['points'] if p['value'] is not None]
            assert present, (device['name'], metric, 'no historical values')
            series.append({'metric': metric, 'source': result['source'], 'presentPoints': len(present)})
        report['devices'].append({'id': identifier, 'name': device['name'], 'address': item['host'], 'slot': item['slot'],
            'connectionRevision': connection['revision'], 'status': connection['status'], 'observedAt': reading['observedAt'],
            'ageSeconds': round(age, 1), 'identity': identity, 'health': reading['health'], 'metrics': reading['metrics'],
            'sensorCount': len(reading['sensors']), 'capabilities': reading['capabilities'], 'qualityFlags': reading['qualityFlags'],
            'series': series})
    report['worker'] = next(x for x in get('/collectors')['items'] if x['id'] == 'device-poller')
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'report': str(args.report), 'verifiedDevices': len(report['devices'])}, ensure_ascii=False))


if __name__ == '__main__':
    main()
