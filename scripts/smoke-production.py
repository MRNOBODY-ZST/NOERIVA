#!/usr/bin/env python3
"""Exercise the real local control→Kafka→ClickHouse/MySQL path with synthetic inputs."""
import base64
import datetime as dt
import json
from pathlib import Path
import time
import urllib.request
import urllib.error
import uuid
import os

root = Path(__file__).resolve().parents[1]
env = dict(line.split("=", 1) for line in (root / ".env").read_text().splitlines() if line and not line.startswith("#"))
base = os.environ.get("NOERIVA_SMOKE_API", "http://127.0.0.1:18080")
def basic(user, password):
    return "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()
def request(path, auth, body=None, expected=200):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, headers={"Authorization": auth, "Content-Type": "application/json", "X-Noeriva-Request": "1"})
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            assert response.status == expected, (path, response.status, expected)
            return json.load(response)
    except urllib.error.HTTPError as error:
        details = error.read().decode()
        raise AssertionError((path, error.code, details)) from None

session = request("/api/v1/session", basic("admin", env["NOERIVA_BOOTSTRAP_PASSWORD"]))
auth = "Bearer " + session["accessToken"]
run_id = uuid.uuid4().hex[:12]
device = request("/api/v1/devices", auth, {"name": f"synthetic-smoke-{run_id}", "type": "ROUTER", "siteId": "default", "vendor": "Simulator", "model": "Synthetic-only", "managementAddress": "192.0.2.11"}, 201)
interface = request(f"/api/v1/devices/{device['id']}/interfaces", auth, {"name": "synthetic0", "speedBps": "1000000000", "macAddress": "02:00:00:00:00:11"}, 201)
now = dt.datetime.now(dt.timezone.utc)
event = {"id": f"smoke-{run_id}", "deviceId": device["id"], "sourceId": "primary", "kind": "DeviceSummaryObserved", "epoch": f"smoke-{run_id}", "sequence": 1, "observedAt": now.isoformat(), "health": "WARNING", "metrics": {"cpu_percent": 41.25}, "message": "SYNTHETIC integration verification"}
collector = basic("collector", env["NOERIVA_COLLECTOR_PASSWORD"])
batch = {"batchId": f"batch-{run_id}", "events": [event]}
accepted = request("/api/v1/ingest/batches", collector, batch, 202)
assert accepted["status"] == "DURABLY_QUEUED"
deadline = time.monotonic() + 40
while True:
    summary = request(f"/api/v1/devices/{device['id']}/summary", auth)
    if summary["sources"]:
        break
    if time.monotonic() > deadline:
        raise AssertionError("Durably accepted state did not become queryable")
    time.sleep(0.4)
assert summary["device"]["health"] == "WARNING"
assert summary["sources"][0]["metrics"]["cpu_percent"] == 41.25
assert summary["sources"][0]["sequence"] == 1
revision = summary["device"]["revision"]
request("/api/v1/ingest/batches", collector, batch, 202)
time.sleep(1)
replayed = request(f"/api/v1/devices/{device['id']}/summary", auth)
assert replayed["device"]["revision"] == revision, "Replay changed projection revision"
assert replayed["sources"][0]["observedAt"] == summary["sources"][0]["observedAt"]
events = request(f"/api/v1/devices/{device['id']}/activity", auth)
assert sum(item["id"] == event["id"] for item in events["items"]) == 1
alerts = request(f"/api/v1/alerts?deviceId={device['id']}", auth)["items"]
assert len(alerts) == 1
ack = request(f"/api/v1/alerts/{alerts[0]['id']}/acknowledge", auth, {"revision": alerts[0]["revision"]})
assert ack["state"] == "ACKNOWLEDGED"
heatmap = request(f"/api/v1/devices/{device['id']}/interfaces/{interface['id']}/bandwidth/heatmap?timezone=UTC", auth)
assert len(heatmap["cells"]) == 168
assert all(cell["value"] is None for cell in heatmap["cells"]), "Uncollected interface invented metric values"
output = {"status": "PASS", "mode": "CONNECTED", "synthetic": True, "runId": run_id, "deviceId": device["id"], "interfaceId": interface["id"], "checks": ["opaque session", "transactional create", "interface ownership", "Kafka durable acceptance", "MySQL projection", "replay idempotence", "ClickHouse history read", "alert acknowledgement", "missing heatmap remains null"]}
(root / "docs/implementation").mkdir(parents=True, exist_ok=True)
(root / "docs/implementation/production-smoke.json").write_text(json.dumps(output, indent=2) + "\n")
print(json.dumps(output, indent=2))
