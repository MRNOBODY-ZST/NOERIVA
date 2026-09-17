#!/usr/bin/env python3
"""Read-only acceptance against the two explicitly authorized deployment hosts.

NOERIVA_BOOTSTRAP_PASSWORD must be supplied by the caller. No credentials or
individual NAT endpoints are written to the result. Run after two application polls.
"""
import argparse, base64, concurrent.futures, datetime as dt, json, math, os, statistics, threading, time
import urllib.error, urllib.parse, urllib.request
from pathlib import Path

DELL = '5a42681b-fe62-4c0f-b138-6971976479f0'
CISCO = 'a720db9a-6439-4b2a-9afa-772efa391976'
HOSTS = ['http://192.168.4.62', 'http://192.168.4.63']
thread = threading.local()
METRICS = {'cpu_percent': '%', 'memory_percent': '%',
           'bandwidth_rx_bps': 'bps', 'bandwidth_tx_bps': 'bps'}
NAT_BASE_FLAGS = {'UDP_UNAUTHENTICATED', 'COMPLETENESS_NOT_GUARANTEED'}

def opener():
    if not hasattr(thread, 'opener'):
        thread.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    return thread.opener

def request(base, path, authorization):
    req = urllib.request.Request(base + '/api/v1' + path, headers={'Authorization': authorization})
    try:
        with opener().open(req, timeout=15) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise AssertionError(f'HTTP_{error.code} {path.split("?")[0]}') from None

def iso(value):
    return dt.datetime.fromisoformat(value.replace('Z', '+00:00'))

def fresh(value, now, seconds=240):
    return value is not None and -30 <= (now - iso(value)).total_seconds() <= seconds

def finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)

def counter64(value):
    return value is None or (isinstance(value, str) and 1 <= len(value) <= 20
        and value.isascii() and value.isdigit() and int(value) <= 2**64 - 1)

def check_snmp_reading(device, source, now):
    assert source['enabled'] and fresh(source['lastSuccessAt'], now), 'SNMP_NOT_FRESH'
    assert source['status'] in ('SUCCESS', 'PARTIAL') and not source.get('errorCode'), 'SNMP_COLLECTION_FAILED'
    reading = source['lastReading']
    assert reading and fresh(reading['observedAt'], now), 'SNMP_READING_NOT_FRESH'
    assert len(reading['ports']) >= (65 if device == DELL else 15), 'INTERFACES_MISSING'
    for metric in ('cpu_percent', 'memory_percent'):
        sensors = [s for s in reading['sensors'] if s['metric'] == metric]
        assert sensors and all(s['unit'] == '%' and finite(s['value']) and 0 <= s['value'] <= 100
                               and s.get('sourceRef') for s in sensors), 'SENSOR_VALUE_INVALID'
    # Multiple entities remain separate; do not demand an invented device aggregate.
    for metric, value in reading['metrics'].items():
        if metric in METRICS:
            assert finite(value) and value >= 0 and (METRICS[metric] != '%' or value <= 100), 'SNMP_AGGREGATE_INVALID'
    for port in reading['ports']:
        assert counter64(port.get('inOctets')) and counter64(port.get('outOctets')), 'SNMP_COUNTER_PRECISION'
    limit_flags = {'SNMP_INTERFACE_LIMIT', 'SNMP_VARIABLE_BUDGET',
                   'SNMP_INTERFACE_IDENTITY_MISSING', 'SNMP_DUPLICATE_INTERFACE_IDENTITY'}
    return {'deviceId': device, 'status': source['status'], 'ports': len(reading['ports']),
        'sensors': len(reading['sensors']), 'lastSuccessAt': source['lastSuccessAt'],
        'qualityFlags': reading['qualityFlags'], 'metricNames': sorted(reading['metrics']),
        'interfaceCoverage': 'LIMITED_OR_PARTIAL' if limit_flags.intersection(reading['qualityFlags'])
            else 'NO_INTERFACE_TRUNCATION_REPORTED',
        'aggregateAvailability': {metric: metric in reading['metrics']
                                  for metric in ('cpu_percent', 'memory_percent')}}

def check_metric_response(response, device, metric, start, end, now):
    assert response['deviceId'] == device and response['metric'] == metric, 'METRIC_SCOPE'
    assert response['unit'] == METRICS[metric] and response['source'] == 'VICTORIAMETRICS', 'METRIC_SOURCE_OR_UNIT'
    assert iso(response['from']) == start and iso(response['to']) == end, 'METRIC_WINDOW'
    points = response['points']
    assert isinstance(points, list) and len(points) <= 120, 'METRIC_POINT_BOUND'
    valid = []
    previous = None
    for point in points:
        at = iso(point['timestamp'])
        assert start <= at <= end and (previous is None or previous < at), 'METRIC_POINT_ORDER_OR_WINDOW'
        previous = at
        value = point['value']
        if value is not None:
            assert finite(value) and value >= 0 and (METRICS[metric] != '%' or value <= 100), 'METRIC_VALUE_INVALID'
            valid.append(point)
    assert valid, 'METRIC_HISTORY_EMPTY'
    assert response['sourceFreshness'] == 'FRESH' and fresh(valid[-1]['timestamp'], now), 'METRIC_HISTORY_STALE'
    assert response.get('asOf') is not None and iso(response['asOf']) == iso(valid[-1]['timestamp']), 'METRIC_ASOF'
    assert finite(response['coverage']) and 0 < response['coverage'] <= 1, 'METRIC_COVERAGE'
    return {'metric': metric, 'unit': response['unit'], 'source': response['source'],
        'points': len(points), 'validPoints': len(valid), 'latestAt': valid[-1]['timestamp'],
        'latestValue': valid[-1]['value'], 'coverage': response['coverage'],
        'qualityFlags': response['qualityFlags']}

def check_application_rows(rows, interface_index, direction, start, end, now):
    assert rows, 'APPLICATION_INTERFACE_HISTORY_EMPTY'
    assert all(o['deviceId'] == CISCO and o['interfaceIndex'] == interface_index
               and o['direction'] == direction and start <= iso(o['observedAt']) < end for o in rows), 'APPLICATION_SCOPE'
    derived = []
    for observation in rows:
        assert counter64(observation['bytes']) and counter64(observation['packets']), 'COUNTER64_PRECISION'
        for field in ('derivedBps', 'reportedBps', 'derivedPacketsPerSecond'):
            value = observation.get(field)
            assert value is None or finite(value) and value >= 0, 'APPLICATION_RATE_INVALID'
        if observation['derivedBps'] is not None:
            assert observation['bytes'] is not None and finite(observation['intervalSeconds']) \
                and observation['intervalSeconds'] > 0, 'APPLICATION_DERIVED_EVIDENCE'
            assert not {'NBAR_BASELINE_REQUIRED', 'NBAR_COUNTER_RESET', 'NBAR_GAP'}.intersection(
                observation['qualityFlags']), 'APPLICATION_INVALID_BASELINE'
            if fresh(observation['observedAt'], now):
                derived.append(observation)
    assert derived, 'APPLICATION_INTERFACE_BASELINE_NOT_READY'
    return {'interfaceIndex': interface_index, 'direction': direction, 'rows': len(rows),
        'freshDerivedRows': len(derived), 'latestDerivedAt': max(derived, key=lambda o: iso(o['observedAt']))['observedAt'],
        'applications': len({o['application'] for o in rows}),
        'qualityFlags': sorted({flag for o in rows for flag in o['qualityFlags']})}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    password = os.environ['NOERIVA_BOOTSTRAP_PASSWORD']
    basic = 'Basic ' + base64.b64encode(('admin:' + password).encode()).decode()
    now = dt.datetime.now(dt.timezone.utc)
    auth = 'Bearer ' + request(HOSTS[0], '/session', basic)['accessToken']
    start = (now - dt.timedelta(hours=1)).isoformat()
    query = urllib.parse.urlencode({'deviceId': CISCO, 'from': start, 'to': now.isoformat(), 'limit': 100})
    paths = ['/applications/sources', '/nat-audit/sources', '/applications/observations?' + query,
             '/nat-audit/events?' + query]
    hosts = []
    for base in HOSTS:
        network = []
        for device in (DELL, CISCO):
            for attempt in range(5):
                rows = request(base, '/devices/' + device + '/connections', auth)['items']
                source = next(r for r in rows if r['slot'] == 'snmp')
                if source['status'] != 'RUNNING' or attempt == 4:
                    break
                time.sleep(0.5)
            network_result = check_snmp_reading(device, source, dt.datetime.now(dt.timezone.utc))
            histories = []
            for hours in (1, 24):
                metric_start = now - dt.timedelta(hours=hours)
                for metric in METRICS:
                    params = urllib.parse.urlencode({'metric': metric, 'from': metric_start.isoformat(),
                                                     'to': now.isoformat(), 'points': 120})
                    history = request(base, '/devices/' + device + '/metrics?' + params, auth)
                    result = check_metric_response(history, device, metric, metric_start, now,
                                                   dt.datetime.now(dt.timezone.utc))
                    histories.append({'windowHours': hours, **result})
            network_result['metricHistory'] = histories
            network.append(network_result)
        for attempt in range(5):
            app = next(s for s in request(base, paths[0], auth)['items'] if s['deviceId'] == CISCO)
            if app['status'] != 'RUNNING' or attempt == 4:
                break
            time.sleep(0.5)
        nat = next(s for s in request(base, paths[1], auth)['items'] if s['deviceId'] == CISCO)
        assert app['enabled'] and app['status'] in ('SUCCESS', 'PARTIAL') and not app.get('errorCode') \
            and fresh(app['lastSuccessAt'], dt.datetime.now(dt.timezone.utc)), 'APPLICATION_NOT_FRESH'
        assert set(app['interfaceIndices']) == {8, 9} and app['lastRowCount'] > 0, 'APPLICATION_INTERFACES'
        assert nat['enabled'] and nat['sourceAddress'] == '192.168.253.1' \
            and fresh(nat['lastPacketAt'], dt.datetime.now(dt.timezone.utc), 120), 'NAT_NOT_FRESH'
        assert nat['status'] in ('RECEIVING', 'DEGRADED'), 'NAT_NOT_RECEIVING'
        assert nat['accepted'] > 0 and nat['persisted'] > 0 \
            and fresh(nat['lastPersistedAt'], dt.datetime.now(dt.timezone.utc), 120), 'NAT_NOT_PERSISTED'
        assert NAT_BASE_FLAGS <= set(nat['qualityFlags']), 'NAT_SOURCE_QUALITY_BOUNDARY'
        observations = request(base, paths[2], auth)['items']
        events = request(base, paths[3], auth)['items']
        assert observations and events, 'HISTORY_EMPTY'
        interface_evidence = []
        for index in (8, 9):
            for direction in ('IN', 'OUT'):
                params = urllib.parse.urlencode({'deviceId': CISCO, 'interfaceIndex': index,
                    'direction': direction, 'from': start, 'to': now.isoformat(), 'limit': 100})
                rows = request(base, '/applications/observations?' + params, auth)['items']
                interface_evidence.append(check_application_rows(rows, index, direction, iso(start), now,
                                                                 dt.datetime.now(dt.timezone.utc)))
        assert all(e['deviceId'] == CISCO and e['sourceAddress'] == nat['sourceAddress']
                   and e['sourceDomain'] == 200 and e['provenance'] == 'CISCO_NAT_HSL_V9'
                   and iso(start) <= iso(e['receivedAt']) < now for e in events), 'NAT_SCOPE'
        assert any(fresh(e['receivedAt'], dt.datetime.now(dt.timezone.utc), 120) for e in events), 'NAT_EVENT_HISTORY_STALE'
        # Passive traffic does not guarantee CREATE and DELETE occur in the newest page.
        assert all(NAT_BASE_FLAGS <= set(e['qualityFlags']) for e in events), 'NAT_QUALITY_BOUNDARY'
        hosts.append({'baseUrl': base, 'network': network,
            'application': {**{k: app[k] for k in ('revision', 'enabled', 'status', 'lastRowCount', 'interfaceIndices', 'lastSuccessAt', 'qualityFlags')},
                'interfaceEvidence': interface_evidence},
            'nat': {**{k: nat[k] for k in ('revision', 'enabled', 'status', 'sourceAddress', 'received', 'accepted', 'persisted', 'dropped', 'parseErrors', 'unknownTemplates', 'sequenceGaps', 'lastPacketAt', 'lastPersistedAt', 'qualityFlags')},
                'currentErrorPresent': bool(nat.get('lastError')),
                'acceptance': 'LIVE_WITH_DIAGNOSTICS' if nat['status'] == 'DEGRADED' else 'LIVE'},
            'querySample': {'applicationRows': len(observations), 'applications': len({o['application'] for o in observations}),
                'derivedRateRows': sum(o['derivedBps'] is not None for o in observations), 'natRows': len(events),
                'natTypes': sorted({e['eventType'] for e in events}), 'natTemplates': sorted({e['templateId'] for e in events})}})
    def one(i):
        opener()  # TLS/proxy handler setup is excluded from measured HTTP latency.
        tick = time.monotonic()
        result = request(HOSTS[i % 2], paths[(i // 2) % len(paths)], auth)
        assert 'items' in result
        return round((time.monotonic() - tick) * 1000, 2)
    with concurrent.futures.ThreadPoolExecutor(max_workers=8, initializer=opener) as pool:
        latency = list(pool.map(one, range(40)))
    result = {'verifiedAt': now.isoformat(), 'hosts': hosts,
        'boundedReadLoad': {'requests': 40, 'concurrency': 8, 'errors': 0,
            'p50Millis': statistics.median(latency), 'p95Millis': sorted(latency)[37], 'maxMillis': max(latency)},
        'captureBoundary': 'Observed UDP events; completeness, pre-enablement history and identity attribution are not asserted.',
        'deploymentBoundary': 'Both LAN HTTP entrypoints were read; pod placement/image readiness and failover are checked separately.',
        'switchBoundary': 'This read-only run observes separate enabled states; independent toggle behavior is covered by integration tests.',
        'historyBoundary': 'Nonempty fresh VM windows are verified; full 24-hour coverage and ClickHouse interface heatmaps are not asserted.'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'hostsVerified': len(hosts), 'load': result['boundedReadLoad']}, ensure_ascii=False))

if __name__ == '__main__':
    main()
