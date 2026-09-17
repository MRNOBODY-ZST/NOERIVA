#!/usr/bin/env python3
"""Append deterministic synthetic samples after the existing local fixture window.

Never rewrites the original six-hour fixture; sends one synthetic current event.
"""
import importlib.util
import json
import math
from pathlib import Path
import time
import urllib.parse

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('local_fixture', ROOT / 'scripts/mock-local.py')
local = importlib.util.module_from_spec(spec)
spec.loader.exec_module(local)
PROGRESS = ROOT / '.local/mock-telemetry-refresh.json'


def main():
    fixture = json.loads(local.STATE.read_text())
    settings = dict(line.split('=', 1) for line in (ROOT / '.env').read_text().splitlines() if line and not line.startswith('#') and '=' in line)
    password = lambda key: settings[key].strip().strip('"').strip("'")
    session = local.request(local.API, '/api/v1/session', local.basic('admin', password('NOERIVA_BOOTSTRAP_PASSWORD')))
    if session['mode'] != 'CONNECTED' or session['organizationId'] != fixture['organizationId']:
        raise local.FixtureFailure('Wrong fixture profile or organization')
    auth = 'Bearer ' + session['accessToken']
    progress = json.loads(PROGRESS.read_text()) if PROGRESS.exists() else {'deviceId':fixture['deviceId'],'until':fixture['endEpoch']}
    if progress['deviceId'] != fixture['deviceId']:
        raise local.FixtureFailure('Refresh fixture identity mismatch')
    until = int(time.time()) // 15 * 15
    if until-fixture['endEpoch'] > 172800:
        raise local.FixtureFailure('This fixture refresh is bounded to two days; create a new named fixture for later dates')
    original, current = local.series(fixture)
    rx, tx = original[-2]['values'][-1], original[-1]['values'][-1]
    rows = [{**row, 'values':[], 'timestamps':[]} for row in original]
    for timestamp in range(fixture['endEpoch']+15,until+1,15):
        index=(timestamp-fixture['startEpoch'])//15
        phase=2*math.pi*index/1440
        values=[44+19*math.sin(phase*2)+7*math.sin(phase*13),61+6*math.sin(phase-.4),48+9*math.sin(phase*2-.3),198+32*math.sin(phase*2),54e6+30e6*math.sin(phase-.5)+6e6*math.sin(phase*9),19e6+10e6*math.sin(phase*2+.4)]
        rx+=round(values[4]*15/8);tx+=round(values[5]*15/8)
        current=dict(zip(current, map(lambda value:round(value,3),values)))
        if timestamp<=progress['until']:
            continue
        for row,value in zip(rows,[*values,rx,tx]):
            row['timestamps'].append(timestamp*1000);row['values'].append(round(value,3))
    if rows[0]['values']:
        local.request(local.VM,'/api/v1/import',body=b''.join(json.dumps(row).encode()+b'\n' for row in rows),expected=204)
        progress['until']=until
        PROGRESS.write_text(json.dumps(progress,indent=2)+'\n')
    sequence=int(time.time()*1000)
    event={'id':f'mock-clarity-refresh-{sequence}','deviceId':fixture['deviceId'],'sourceId':'primary','kind':'DeviceSummaryObserved','epoch':fixture['epoch'],'sequence':sequence,'observedAt':local.iso(until),'health':'WARNING','metrics':current,'message':'MOCK Clarity: deterministic synthetic telemetry refresh; no device contacted.'}
    local.request(local.API,'/api/v1/ingest/batches',local.basic('collector',password('NOERIVA_COLLECTOR_PASSWORD')),{'batchId':event['id'],'events':[event]},202)
    local.until(lambda:local.request(local.API,f"/api/v1/devices/{fixture['deviceId']}/summary",auth),lambda response:any(s['sequence']==sequence for s in response['sources']),30,'Current mock state did not become visible')
    params=urllib.parse.urlencode({'metric':'cpu_percent','from':local.iso(until-3600),'to':local.iso(until),'points':120})
    metric=local.until(lambda:local.request(local.API,f"/api/v1/devices/{fixture['deviceId']}/metrics?{params}",auth),lambda value:len(value['points'])>=100,30,'Mock current-hour trend missing points')
    if len(metric['points'])<100:
        raise local.FixtureFailure('Mock current-hour trend missing points')
    print(json.dumps({'synthetic':True,'deviceId':fixture['deviceId'],'appendedPointsPerSeries':len(rows[0]['values']),'latest':local.iso(until),'currentHourMetricPoints':len(metric['points'])},indent=2))

if __name__=='__main__':
    try:main()
    except local.FixtureFailure as error:raise SystemExit(str(error)) from None
