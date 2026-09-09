#!/usr/bin/env python3
"""Bounded local HTTP smoke benchmark. Never a production capacity certification."""
import argparse
import base64
import concurrent.futures
import datetime as dt
import http.client
import json
import os
from pathlib import Path
import platform
import statistics
import threading
import time
import urllib.parse
import uuid

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--url", default="http://127.0.0.1:18080")
p.add_argument("--requests", type=int, default=1000)
p.add_argument("--concurrency", type=int, default=32)
p.add_argument("--seed-devices", type=int, default=0)
args = p.parse_args()
if not 1 <= args.requests <= 100000 or not 1 <= args.concurrency <= 128 or not 0 <= args.seed_devices <= 10000:
    p.error("Requests1..100000, concurrency1..128, synthetic seed0..10000")
target = urllib.parse.urlparse(args.url)
if target.hostname not in ("127.0.0.1", "localhost", "::1"):
    p.error("This synthetic benchmark only targets loopback; use a reviewed staging load plan for remote systems")
root = Path(__file__).resolve().parents[1]
env = dict(line.split("=", 1) for line in (root / ".env").read_text().splitlines() if line and not line.startswith("#"))
local = threading.local()
def connection():
    if not hasattr(local, "connection"):
        local.connection = http.client.HTTPConnection(target.hostname, target.port or 80, timeout=20)
    return local.connection
def call(path, auth, body=None):
    conn = connection()
    conn.request("GET" if body is None else "POST", path, body=None if body is None else json.dumps(body), headers={"Authorization": auth, "Content-Type": "application/json", "X-Noeriva-Request": "1"})
    response = conn.getresponse()
    raw = response.read()
    return response.status, raw
status, raw = call("/api/v1/session", "Basic " + base64.b64encode(("admin:" + env["NOERIVA_BOOTSTRAP_PASSWORD"]).encode()).decode())
assert status == 200, status
session = json.loads(raw)
auth = "Bearer " + session["accessToken"]
if args.seed_devices:
    run = uuid.uuid4().hex[:8]
    def seed(index):
        status, _ = call("/api/v1/devices", auth, {"name": f"benchmark-{run}-{index:05d}", "type": "HOST", "siteId": "default", "vendor": "Synthetic", "model": "Benchmark fixture", "managementAddress": f"192.0.2.{index % 254 + 1}"})
        assert status == 201, status
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(seed, range(args.seed_devices)))
overview = json.loads(call("/api/v1/overview", auth)[1])
device = json.loads(call("/api/v1/devices?limit=1", auth)[1])["items"][0]
paths = ["/api/v1/devices?limit=100", f"/api/v1/devices/{device['id']}/summary", "/api/v1/alerts?limit=50"]
def measured(index):
    started = time.perf_counter()
    try:
        status, body = call(paths[index % len(paths)], auth)
        return {"milliseconds": (time.perf_counter()-started)*1000, "status": status, "bytes": len(body), "path": paths[index % len(paths)]}
    except Exception as error:
        local.__dict__.pop("connection", None)
        return {"milliseconds": (time.perf_counter()-started)*1000, "status": "transport_error", "bytes": 0, "path": paths[index % len(paths)], "error": type(error).__name__}
def summarize(rows, elapsed):
    times = sorted(row["milliseconds"] for row in rows)
    q = lambda value: round(times[min(len(times)-1, int((len(times)-1)*value))], 2)
    statuses = {}
    for row in rows:
        statuses[str(row["status"])] = statuses.get(str(row["status"]), 0)+1
    return {"requests": len(rows), "elapsedSeconds": round(elapsed, 3), "requestsPerSecond": round(len(rows)/elapsed, 2), "p50Ms": q(.50), "p95Ms": q(.95), "p99Ms": q(.99), "statusCounts": statuses, "responseBytes": sum(row["bytes"] for row in rows)}
rounds = {}
for name in ("first_pass", "warm_pass"):
    start = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        rows = list(pool.map(measured, range(args.requests)))
    rounds[name] = summarize(rows, time.perf_counter()-start)
report = {"measuredAt": dt.datetime.now(dt.timezone.utc).isoformat(), "kind": "LOCAL_COMPACT_SMOKE_NOT_CAPACITY_ACCEPTANCE", "mode": session["mode"], "host": {"platform": platform.platform(), "machine": platform.machine(), "logicalCpus": os.cpu_count()}, "concurrency": args.concurrency, "inventoryDevices": overview["devices"], "queryMixture": paths, "rounds": rounds, "limitations": ["Synthetic local dataset; no long-history soak or node failure", "first_pass is not a controlled cold-disk/cache test", "No sustained concurrent telemetry writes in this smoke workload", "Not an achieved production SLO or maximum supported capacity"]}
output = root / "docs/implementation/benchmark-smoke.json"
output.write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
