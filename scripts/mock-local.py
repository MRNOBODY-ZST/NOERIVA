#!/usr/bin/env python3
"""Create/reuse one synthetic CUA fixture through local connected APIs only.

Run from any directory: python3 scripts/mock-local.py
Keeps non-secret identity/progress in ignored .local/mock-cua-fixture.json.
Uses no SQL, real-device access, deletion, remote endpoints, or background process.
Leaves the WARNING alert available for a human/browser acknowledgement test.
"""
import base64
import datetime as dt
import json
import math
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / '.local/mock-cua-fixture.json'
API = 'http://127.0.0.1:18080'
VM = 'http://127.0.0.1:18428'
CADENCE = 15
WINDOW = 6 * 3600


class FixtureFailure(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        raise FixtureFailure('Local fixture endpoints must not redirect')


HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())


def request(base, path, auth=None, body=None, expected=200, ndjson=False):
    if base not in (API, VM) or not path.startswith('/'):
        raise FixtureFailure('Fixture requests are limited to the two fixed loopback endpoints')
    headers = {'Content-Type': 'application/json', 'X-Noeriva-Request': '1'}
    if auth:
        headers['Authorization'] = auth
    raw = body if isinstance(body, bytes) else None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=raw, headers=headers)
    try:
        with HTTP.open(req, timeout=15) as response:
            if response.status != expected:
                raise FixtureFailure(f'Unexpected status {response.status} for {path.split("?")[0]}')
            payload = response.read(4 * 1024 * 1024 + 1)
            if len(payload) > 4 * 1024 * 1024:
                raise FixtureFailure('Fixture response exceeded 4 MiB')
            if ndjson:
                return [json.loads(line) for line in payload.splitlines() if line.strip()]
            return json.loads(payload) if payload else None
    except urllib.error.HTTPError as error:
        # Never print request headers, bearer tokens, credentials or provider bodies.
        raise FixtureFailure(f'HTTP {error.code} for {path.split("?")[0]}') from None
    except urllib.error.URLError:
        raise FixtureFailure(f'Local endpoint unavailable: {base}') from None


def basic(username, password):
    return 'Basic ' + base64.b64encode(f'{username}:{password}'.encode()).decode()


def iso(timestamp):
    return dt.datetime.fromtimestamp(timestamp, dt.timezone.utc).isoformat().replace('+00:00', 'Z')


def save(state):
    STATE.parent.mkdir(parents=True, exist_ok=True)
    temporary = STATE.with_suffix('.tmp')
    temporary.write_text(json.dumps(state, indent=2) + '\n')
    temporary.replace(STATE)


def until(action, predicate, seconds, reason):
    deadline = time.monotonic() + seconds
    while True:
        result = action()
        if predicate(result):
            return result
        if time.monotonic() >= deadline:
            raise FixtureFailure(reason)
        time.sleep(0.5)


def series(state):
    timestamps = [(state['startEpoch'] + i * CADENCE) * 1000 for i in range(WINDOW // CADENCE + 1)]
    values = {name: [] for name in ('cpu_percent', 'memory_percent', 'temperature_celsius', 'power_watts',
                                    'bandwidth_rx_bps', 'bandwidth_tx_bps')}
    rx, tx = [1_000_000_000], [500_000_000]
    for i in range(len(timestamps)):
        phase = 2 * math.pi * i / (len(timestamps) - 1)
        current = {
            'cpu_percent': 44 + 19 * math.sin(phase * 2) + 7 * math.sin(phase * 13),
            'memory_percent': 61 + 6 * math.sin(phase - 0.4),
            'temperature_celsius': 48 + 9 * math.sin(phase * 2 - 0.3),
            'power_watts': 198 + 32 * math.sin(phase * 2),
            'bandwidth_rx_bps': 54_000_000 + 30_000_000 * math.sin(phase - 0.5) + 6_000_000 * math.sin(phase * 9),
            'bandwidth_tx_bps': 19_000_000 + 10_000_000 * math.sin(phase * 2 + 0.4),
        }
        for name, value in current.items():
            values[name].append(round(value, 3))
        if i:
            rx.append(rx[-1] + round(current['bandwidth_rx_bps'] * CADENCE / 8))
            tx.append(tx[-1] + round(current['bandwidth_tx_bps'] * CADENCE / 8))
    labels = {'organization_id': state['organizationId'], 'device_id': state['deviceId'], 'source_id': 'primary'}
    rows = [{'metric': {**labels, '__name__': 'noeriva_' + name}, 'values': data, 'timestamps': timestamps}
            for name, data in values.items()]
    for direction, counter in (('receive', rx), ('transmit', tx)):
        rows.append({'metric': {**labels, '__name__': f'noeriva_interface_{direction}_bytes_total',
                               'interface_id': state['interfaceId'], 'source_epoch': state['epoch']},
                     'values': counter, 'timestamps': timestamps})
    return rows, {name: data[-1] for name, data in values.items()}


def main():
    settings = {}
    for line in (ROOT / '.env').read_text().splitlines():
        if line and not line.startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            settings[key] = value.strip().strip('"').strip("'")
    session = request(API, '/api/v1/session', basic('admin', settings['NOERIVA_BOOTSTRAP_PASSWORD']))
    if session['mode'] != 'CONNECTED':
        raise FixtureFailure('The fixture requires the local CONNECTED profile')
    auth = 'Bearer ' + session['accessToken']
    collector = basic('collector', settings['NOERIVA_COLLECTOR_PASSWORD'])
    state = json.loads(STATE.read_text()) if STATE.exists() else {}
    if state and (state.get('organizationId') != session['organizationId'] or not state.get('name', '').startswith('mock-cua-')):
        raise FixtureFailure('Existing fixture metadata does not match the current mock organization')
    if not state:
        run = uuid.uuid4().hex[:10]
        end = int(time.time()) // 300 * 300
        state = {'synthetic': True, 'mode': 'CONNECTED', 'name': f'mock-cua-router-{run}',
                 'runId': run, 'epoch': f'mock-cua-{run}', 'organizationId': session['organizationId'],
                 'startEpoch': end - WINDOW, 'endEpoch': end, 'from': iso(end - WINDOW), 'to': iso(end),
                 'cadenceSeconds': CADENCE, 'pointsPerSeries': 1441, 'status': 'PREPARING', 'jobs': {}}
        save(state)
    if 'deviceId' not in state:
        sites = request(API, '/api/v1/sites', auth)['items']
        if not sites:
            raise FixtureFailure('No authorized site exists for the fixture')
        site = next((s['id'] for s in sites if s['id'] == 'default'), sites[0]['id'])
        device = request(API, '/api/v1/devices', auth, {'name': state['name'], 'type': 'ROUTER', 'siteId': site,
            'vendor': 'NOERIVA Mock', 'model': 'Synthetic CUA fixture', 'managementAddress': '192.0.2.240'}, 201)
        state.update(deviceId=device['id'], siteId=site)
        save(state)
    if 'interfaceId' not in state:
        interface = request(API, f"/api/v1/devices/{state['deviceId']}/interfaces", auth,
            {'name': 'mock-cua-eth0', 'speedBps': '1000000000', 'macAddress': '02:00:00:00:00:F0'}, 201)
        state['interfaceId'] = interface['id']
        save(state)
    print(f"Preparing {state['name']} ({state['deviceId']})", flush=True)
    rows, current = series(state)
    if not state.get('rawImported'):
        request(VM, '/api/v1/import', body=b''.join(json.dumps(row).encode() + b'\n' for row in rows), expected=204)
        state['rawImported'] = True
        save(state)
    selector = '{organization_id="' + state['organizationId'] + '",device_id="' + state['deviceId'] + '",source_id="primary"}'
    query = urllib.parse.urlencode({'match[]': selector, 'start': state['from'], 'end': state['to'], 'max_rows_per_line': 2000})
    expected = {row['metric']['__name__']: dict(zip(row['timestamps'], row['values'])) for row in rows}

    def complete(export):
        found = {}
        for row in export:
            name = row['metric']['__name__']
            if name not in expected:
                raise FixtureFailure('Unexpected series in fixture scope')
            points = found.setdefault(name, {})
            for timestamp, value in zip(row['timestamps'], row['values']):
                if timestamp not in expected[name] or abs(value - expected[name][timestamp]) > 0.001:
                    raise FixtureFailure('Imported mock source values changed')
                points[timestamp] = value
        return len(found) == len(expected) and all(len(found[name]) == 1441 for name in expected)

    until(lambda: request(VM, '/api/v1/export?' + query, ndjson=True), complete, 45,
          'Eight imported series were not fully visible within 45 seconds')
    print('Eight 1441-point series are export-visible; queueing bounded rx/tx repairs.', flush=True)
    for direction in ('rx', 'tx'):
        if direction not in state['jobs']:
            job = request(API, '/api/v1/query-jobs', auth, {'deviceId': state['deviceId'], 'interfaceId': state['interfaceId'],
                'from': state['from'], 'to': state['to'], 'direction': direction}, 202)
            state['jobs'][direction] = {'id': job['id'], 'status': job['status']}
            save(state)
    for direction, recorded in state['jobs'].items():
        job = until(lambda: request(API, '/api/v1/query-jobs/' + recorded['id'], auth),
                    lambda result: result['status'] in ('SUCCEEDED', 'FAILED'), 90, f'{direction} repair exceeded 90 seconds')
        recorded.update(status=job['status'], bucketCount=job['bucketCount'], errorCode=job['errorCode'])
        save(state)
        if job['status'] != 'SUCCEEDED' or job['bucketCount'] != 72:
            raise FixtureFailure(f'{direction} repair failed: {job["errorCode"]}')
    # Send the current warning after repair so its freshness is useful to the CUA session.
    sequence = max(int(time.time() * 1000), state.get('sequence', 0) + 1)
    event_id = f"mock-cua-{state['runId']}-{sequence}"
    observation = {'id': event_id, 'deviceId': state['deviceId'], 'sourceId': 'primary', 'kind': 'DeviceSummaryObserved',
        'epoch': state['epoch'], 'sequence': sequence, 'observedAt': iso(time.time()), 'health': 'WARNING',
        'metrics': current, 'message': 'MOCK CUA fixture: synthetic warning and metric trends; no real device collection.'}
    accepted = request(API, '/api/v1/ingest/batches', collector, {'batchId': event_id, 'events': [observation]}, 202)
    if accepted['status'] != 'DURABLY_QUEUED':
        raise FixtureFailure('Mock observation was not durably queued')
    summary = until(lambda: request(API, f"/api/v1/devices/{state['deviceId']}/summary", auth),
        lambda value: any(s['sourceId'] == 'primary' and s['sequence'] == sequence for s in value['sources']),
        45, 'Kafka observation did not become visible in the source projection')
    if summary['device']['health'] != 'WARNING' or 'metrics' not in summary['device']['capabilities']:
        raise FixtureFailure('Mock state or metric capability was not projected')
    state.update(sequence=sequence, eventId=event_id, sourceObservedAt=observation['observedAt'], metrics={})
    for name in current:
        params = urllib.parse.urlencode({'metric': name, 'from': state['from'], 'to': state['to'], 'points': 240})
        result = request(API, f"/api/v1/devices/{state['deviceId']}/metrics?" + params, auth)
        values = [point['value'] for point in result['points'] if point['value'] is not None]
        if len(values) < 200 or max(values) <= min(values) or result['source'] != 'VICTORIAMETRICS':
            raise FixtureFailure(f'Named metric {name} did not return a varying connected trend')
        state['metrics'][name] = {'points': len(values), 'minimum': min(values), 'maximum': max(values)}
    state['heatmaps'] = {}
    for direction in ('rx', 'tx'):
        params = urllib.parse.urlencode({'timezone': 'Asia/Shanghai', 'direction': direction, 'days': 7})
        heatmap = request(API, f"/api/v1/devices/{state['deviceId']}/interfaces/{state['interfaceId']}/bandwidth/heatmap?" + params, auth)
        observed = [cell for cell in heatmap['cells'] if cell['value'] is not None]
        if len(heatmap['cells']) != 168 or len(observed) < 6 or any(cell['value'] <= 0 for cell in observed):
            raise FixtureFailure(f'{direction} heatmap did not expose six hours of positive bandwidth')
        state['heatmaps'][direction] = {'cells': 168, 'observedCells': len(observed), 'timezone': heatmap['timezone'],
                                      'valuesBps': [cell['value'] for cell in observed], 'dataRevision': heatmap['dataRevision']}
    alerts = request(API, '/api/v1/alerts?' + urllib.parse.urlencode({'deviceId': state['deviceId']}), auth)['items']
    if len(alerts) != 1 or alerts[0]['severity'] != 'WARNING':
        raise FixtureFailure('Expected one source WARNING alert')
    state['alert'] = {'id': alerts[0]['id'], 'state': alerts[0]['state'], 'revision': alerts[0]['revision']}
    until(lambda: request(API, f"/api/v1/devices/{state['deviceId']}/activity", auth),
          lambda events: any(event['id'] == event_id for event in events['items']), 30, 'Mock history event not visible')
    state.update(status='PASS', verifiedAt=iso(time.time()), detailPath=f"/devices/{state['deviceId']}",
                 checks=['connected current WARNING through Kafka', 'six varying named metric queries',
                         'two durable repair jobs of 72 buckets', 'rx/tx 168-cell heatmaps', 'one WARNING alert retained',
                         'ClickHouse activity event'])
    save(state)
    print(json.dumps({key: state[key] for key in ('status', 'name', 'deviceId', 'interfaceId', 'from', 'to', 'alert', 'checks')}, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (FixtureFailure, KeyError, ValueError, OSError) as error:
        # Library errors may carry environment details; keep the terminal diagnostic bounded.
        print('Fixture failed: ' + (str(error) if isinstance(error, FixtureFailure) else type(error).__name__))
        raise SystemExit(1) from None
