#!/usr/bin/env python3
"""Seed/reuse local Clarity workbench fixtures and verify real API persistence.

Uses the existing mock-cua device and loopback API only, never contacts target
addresses. Credentials remain in memory. State is ignored under .local/.
"""
import datetime as dt
import hashlib
import importlib.util
import json
from pathlib import Path
import time
import urllib.parse

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('local_fixture', ROOT / 'scripts/mock-local.py')
local = importlib.util.module_from_spec(spec)
spec.loader.exec_module(local)
STATE = ROOT / '.local/mock-workbench-fixture.json'


def main():
    settings = dict(line.split('=', 1) for line in (ROOT / '.env').read_text().splitlines()
                    if line and not line.startswith('#') and '=' in line)
    session = local.request(local.API, '/api/v1/session', local.basic('admin', settings['NOERIVA_BOOTSTRAP_PASSWORD'].strip().strip('"').strip("'")))
    if session['mode'] != 'CONNECTED':
        raise local.FixtureFailure('Local connected profile required')
    auth = 'Bearer ' + session['accessToken']
    fixture = json.loads((ROOT / '.local/mock-cua-fixture.json').read_text())
    device = fixture['deviceId']
    state = json.loads(STATE.read_text()) if STATE.exists() else {'deviceId': device, 'organizationId': session['organizationId'], 'atEpoch': int(time.time()), 'records': {}}
    if state['deviceId'] != device or state['organizationId'] != session['organizationId']:
        raise local.FixtureFailure('Fixture organization/device mismatch')
    def save():
        STATE.write_text(json.dumps(state, indent=2) + '\n')
    def api(path, body=None, expected=200):
        return local.request(local.API, '/api/v1' + path, auth, body, expected)
    def seed(key, path, body):
        if key not in state['records']:
            result = api('/workbench' + path, body, 201)
            state['records'][key] = result['id']
            save()
        return state['records'][key]
    at = local.iso(state['atEpoch'])
    incident = seed('incident', '/incidents', {'title': 'Mock · 主机温度与接口异常联合检查', 'severity': 'WARNING', 'deviceId': device, 'alertId': None, 'assignee': 'admin', 'note': '合成场景：两个信号时间接近，尚未确认根因。请核实传感器与接口来源。'})
    existing = api('/workbench/incidents/' + incident)
    if existing['status'] == 'OPEN':
        api('/workbench/incidents/' + incident + '/updates', {'revision': existing['revision'], 'title': existing['title'], 'status': 'INVESTIGATING', 'assignee': 'admin', 'note': 'Mock 初始化：已开始调查，等待独立诊断记录。'})
    text = json.dumps({'synthetic': True, 'deviceId': device, 'observedAt': at, 'observation': 'temperature and interface error signals require independent review', 'causality': 'UNCONFIRMED'}, ensure_ascii=False, indent=2)
    evidence = seed('evidence', '/evidence', {'deviceId': device, 'title': 'Mock · 巡检观察记录', 'kind': 'OBSERVATION', 'source': 'mock-clarity-fixture', 'observedAt': at, 'content': text, 'provenance': 'SYNTHETIC'})
    config = ['hostname mock-cua-router', 'service timestamps log datetime', 'logging host 192.0.2.61', 'username mockaudit secret SYNTHETIC_NOT_A_REAL_SECRET', 'interface mock-cua-eth0', ' description synthetic uplink', ' no shutdown']
    before = seed('before', '/configuration/snapshots', {'deviceId': device, 'title': 'Mock · cfg-109', 'source': 'mock-clarity-fixture', 'capturedAt': at, 'content': '\n'.join(config), 'provenance': 'SYNTHETIC'})
    config[1] = 'service timestamps log datetime msec localtime'
    after = seed('after', '/configuration/snapshots', {'deviceId': device, 'title': 'Mock · cfg-110', 'source': 'mock-clarity-fixture', 'capturedAt': at, 'content': '\n'.join(config), 'provenance': 'SYNTHETIC'})
    for key, name, kind, target in [('tcp', 'Mock · 管理入口 TCP', 'TCP', '192.0.2.240:22'), ('tls', 'Mock · BMC TLS', 'TLS', '192.0.2.240:443'), ('empty', 'Mock · 等待首次上报', 'DNS', 'fixture.invalid')]:
        check = seed(key, '/checks', {'deviceId': device, 'name': name, 'type': kind, 'target': target, 'intervalSeconds': 60, 'enabled': True, 'provenance': 'SYNTHETIC'})
        if key != 'empty':
            for index in range(30):
                fail = key == 'tcp' and index >= 27
                seed(f'{key}-result-{index}', '/check-results', {'id': f'clarity-{state["atEpoch"]}-{key}-{index}', 'checkId': check, 'observedAt': local.iso(state['atEpoch'] - (29-index)*60), 'status': 'FAIL' if fail else 'PASS', 'latencyMs': None if fail else 12 + index % 7, 'message': 'Synthetic timeout; not a real network request' if fail else 'Synthetic successful observation', 'source': 'mock-clarity-fixture', 'provenance': 'SYNTHETIC'})
    common = {'deviceId': device, 'privateIp': '192.0.2.240', 'privatePort': None, 'publicIp': None, 'publicPort': None, 'protocol': None, 'validFrom': local.iso(state['atEpoch'] - 3600), 'validTo': local.iso(state['atEpoch'] + 43200), 'lifecycle': 'COMPLETE', 'clockUncertaintyMs': 0, 'source': 'mock-clarity-fixture', 'provenance': 'SYNTHETIC'}
    seed('lease', '/network-evidence', {**common, 'id': f'clarity-lease-{state["atEpoch"]}', 'kind': 'ADDRESS_LEASE'})
    for key, port, private_port, lifecycle, skew in [('confirmed',54021,53188,'COMPLETE',0),('ambiguous-a',54022,53189,'COMPLETE',2000),('ambiguous-b',54022,53190,'COMPLETE',2000),('insufficient',54023,53191,'SNAPSHOT_ONLY',0)]:
        seed(key, '/network-evidence', {**common, 'id': f'clarity-nat-{state["atEpoch"]}-{key}', 'kind': 'NAT', 'privatePort': private_port, 'publicIp': '198.51.100.26', 'publicPort': port, 'protocol': 'TCP', 'lifecycle': lifecycle, 'clockUncertaintyMs': skew})
    results = {}
    for port, expected in [(54021,'CONFIRMED'),(54022,'AMBIGUOUS'),(54023,'INSUFFICIENT_EVIDENCE'),(54024,'NO_MATCH')]:
        answer = api('/workbench/investigations', {'ip':'198.51.100.26','port':port,'protocol':'TCP','at':at,'direction':'PUBLIC_TO_PRIVATE'})
        if answer['status'] != expected:
            raise local.FixtureFailure(f'Investigation outcome mismatch on fixture port {port}')
        results[str(port)] = answer['status']
    manifest = api('/workbench/evidence/' + evidence + '/manifest')
    assert manifest['sha256'] == hashlib.sha256(manifest['evidence']['content'].encode()).hexdigest()
    assert manifest['worm'] is False and manifest['integrity'] == 'UNSIGNED'
    diff = api('/workbench/configuration/diff?' + urllib.parse.urlencode({'before':before,'after':after}))
    assert diff['added'] == 1 and diff['removed'] == 1
    snapshot = api('/workbench/configuration/snapshots/' + after)
    assert 'SYNTHETIC_NOT_A_REAL_SECRET' not in snapshot['content'] and snapshot['redactedLines'] >= 1
    assert api('/workbench/checks/' + state['records']['empty'])['lastResult'] is None
    tcp = api('/workbench/checks/' + state['records']['tcp'])
    if tcp['revision'] == 1:
        assert tcp['lastResult']['status'] == 'FAIL'
    assert len(api('/workbench/checks/' + state['records']['tcp'] + '/results?limit=30')['items']) == 30
    assert api('/workbench/incidents/' + incident)['status'] in ('INVESTIGATING','RESOLVED')
    state.update(verifiedAt=local.iso(time.time()), investigationOutcomes=results, evidenceSha256=manifest['sha256'], diff={'added':diff['added'],'removed':diff['removed']}, synthetic=True)
    save()
    print(json.dumps({'deviceId':device,'incidentId':incident,'records':len(state['records']),'investigations':results,'sha256Verified':True,'redactionVerified':True,'configurationDiff':state['diff'],'unobservedCheck':'UNKNOWN','stateFile':str(STATE)}, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    try:
        main()
    except (local.FixtureFailure, AssertionError) as error:
        raise SystemExit(f'Fixture verification failed: {error}') from None
