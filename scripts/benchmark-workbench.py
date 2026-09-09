#!/usr/bin/env python3
"""Bounded loopback-only mixed read benchmark for the Clarity workbench."""
import argparse
import collections
import concurrent.futures
import importlib.util
import json
from pathlib import Path
import statistics
import time

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('local_fixture',ROOT/'scripts/mock-local.py')
local=importlib.util.module_from_spec(spec)
spec.loader.exec_module(local)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--requests',type=int,default=640)
    parser.add_argument('--concurrency',type=int,default=32)
    args=parser.parse_args()
    if not 1<=args.requests<=10000 or not 1<=args.concurrency<=64:
        raise SystemExit('Requests must be 1–10000; concurrency 1–64')
    settings=dict(line.split('=',1) for line in (ROOT/'.env').read_text().splitlines() if line and not line.startswith('#') and '=' in line)
    password=settings['NOERIVA_BOOTSTRAP_PASSWORD'].strip().strip('"').strip("'")
    session=local.request(local.API,'/api/v1/session',local.basic('admin',password))
    if session['mode']!='CONNECTED':raise SystemExit('CONNECTED local profile required')
    auth='Bearer '+session['accessToken']
    paths=['/workspace/monitoring?limit=30','/workspace/interfaces?limit=30','/workbench/incidents?limit=30','/workbench/evidence?limit=30','/workbench/configuration/snapshots?limit=30','/workbench/checks?limit=30']
    def call(index):
        path=paths[index%len(paths)];start=time.perf_counter()
        try:
            value=local.request(local.API,'/api/v1'+path,auth)
            count=len(value['items']);status='200'
        except local.FixtureFailure as error:
            status=str(error);count=0
        return path,(time.perf_counter()-start)*1000,status,count
    for index in range(len(paths)):call(index)
    start=time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:rows=list(pool.map(call,range(args.requests)))
    elapsed=time.perf_counter()-start
    latencies=sorted(row[1] for row in rows)
    percentile=lambda p:round(latencies[min(len(latencies)-1,int((len(latencies)-1)*p))],2)
    report={'kind':'LOCAL_COMPACT_MIXED_READ_SMOKE','asOf':local.iso(time.time()),'requests':args.requests,'concurrency':args.concurrency,'durationSeconds':round(elapsed,3),'requestsPerSecond':round(args.requests/elapsed,2),'p50Ms':percentile(.5),'p95Ms':percentile(.95),'p99Ms':percentile(.99),'statuses':dict(collections.Counter(row[2] for row in rows)),'queries':paths,'caveat':'Loopback Docker API; bounded current projections and small synthetic workbench records. Not a production capacity or long-history SLO validation.'}
    (ROOT/'.local/clarity-benchmark.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
    if any(row[2]!='200' for row in rows):raise SystemExit(1)
if __name__=='__main__':main()
