#!/usr/bin/env python3
"""Bounded, read-only API acceptance for the two authorized NOERIVA LAN entries.

Only GET requests are issued. Password comes from NOERIVA_BOOTSTRAP_PASSWORD;
no environment file is read. Tokens and API payloads stay in process memory.
The report contains counts, timings, HTTP statuses and fixed safe error codes.
This small deployment check is not a production capacity benchmark.
"""
import argparse
import base64
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import datetime as dt
import json
import math
import os
from pathlib import Path
import re
import socket
import statistics
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ENTRIES = ("http://192.168.4.62", "http://192.168.4.63")
ADDRESSES = tuple("192.168.254." + suffix for suffix in ("1", "3", "10", "11", "12", "20"))
IMANA = ADDRESSES[2:5]
MAX_JSON = 2 * 1024 * 1024
MAX_SSE = 1024 * 1024
SECRET_FIELDS = {"password", "community", "authPassword", "privacyPassword", "ciphertext", "secrets", "accessToken", "leaseToken"}


class Failure(Exception):
    def __init__(self, code, status=0):
        self.code = code if re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", code) else "UNSAFE_ERROR_CODE"
        self.status = status
        super().__init__(self.code)


def require(condition, code):
    if not condition:
        raise Failure(code)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        fp.close()
        raise Failure("REDIRECT_REJECTED", code)


def checked_json(raw, password, allow_token=False):
    try:
        text = raw.decode("utf-8")
        require(password not in text, "SECRET_IN_RESPONSE")
        value = json.loads(text, parse_constant=lambda _: (_ for _ in ()).throw(Failure("NONFINITE_JSON")))
        def inspect(item):
            if isinstance(item, dict):
                forbidden = SECRET_FIELDS - ({"accessToken"} if allow_token else set())
                require(not forbidden.intersection(item), "SECRET_FIELD_IN_RESPONSE")
                for child in item.values():
                    inspect(child)
            elif isinstance(item, list):
                for child in item:
                    inspect(child)
            elif isinstance(item, str):
                require(password not in item, "SECRET_IN_RESPONSE")
        inspect(value)
        require(isinstance(value, dict), "INVALID_RESPONSE_SHAPE")
        return value
    except Failure:
        raise
    except (ValueError, UnicodeError, RecursionError):
        raise Failure("INVALID_JSON") from None


def instant(value):
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        require(parsed.tzinfo is not None, "INVALID_TIMESTAMP")
        return parsed
    except (TypeError, ValueError, AttributeError):
        raise Failure("INVALID_TIMESTAMP") from None


def collection_items(payload):
    items = payload.get("items")
    require(isinstance(items, list) and 1 <= len(items) <= 3, "INVALID_COLLECTION_COUNT")
    require(len({item.get("slot") for item in items}) == len(items), "DUPLICATE_COLLECTION_SLOT")
    for item in items:
        require(item.get("slot") in {"ssh", "redfish", "snmp"}, "INVALID_COLLECTION_SLOT")
        require(not {"host", "port", "username", "certificateSha256", "sshHostKeySha256", "sshProfile"}.intersection(item), "UNSAFE_COLLECTION_SETTINGS")
    return items


class Client:
    def __init__(self, base, password, deadline):
        require(base in ENTRIES, "TARGET_NOT_ALLOWED")
        self.base, self.password, self.deadline = base, password, deadline
        self.token = None
        self.http = Counter()
        self.lock = threading.Lock()
        self.transport = threading.local()

    def prepare_transport(self):
        if not hasattr(self.transport, "opener"):
            # urllib creates a default TLS context even for these HTTP targets.
            # Keep one opener per client/thread; never share mutable handlers.
            self.transport.opener = urllib.request.build_opener(
                urllib.request.ProxyHandler({}), NoRedirect())
        return self.transport.opener

    def count_http(self, status):
        with self.lock:
            self.http[str(status)] += 1

    def request(self, path, session=False, stream=False):
        require(path.startswith("/") and not path.startswith("//") and "#" not in path, "INVALID_API_PATH")
        remaining = self.deadline - time.monotonic()
        require(remaining > 0, "RUN_DEADLINE")
        authorization = ("Basic " + base64.b64encode(("admin:" + self.password).encode()).decode()
                         if session else "Bearer " + (self.token or ""))
        require(session or bool(self.token), "SESSION_REQUIRED")
        request = urllib.request.Request(self.base + "/api/v1" + path, method="GET", headers={
            "Authorization": authorization, "Accept": "text/event-stream" if stream else "application/json",
            "X-Noeriva-Request": "1", "User-Agent": "NOERIVA-read-only-acceptance/1",
        })
        # No proxy discovery, redirect, cookie jar, Basic challenge handler or token-in-URL.
        opener = self.prepare_transport()
        end = min(self.deadline, time.monotonic() + (20 if stream else 15))
        try:
            with opener.open(request, timeout=min(10, remaining)) as response:
                status = response.status
                self.count_http(status)
                require(status == 200, "UNEXPECTED_HTTP_STATUS")
                content_type = response.headers.get_content_type()
                require(content_type == ("text/event-stream" if stream else "application/json"), "UNEXPECTED_CONTENT_TYPE")
                if stream:
                    return self.first_collection(response, end)
                raw = bytearray()
                while True:
                    require(time.monotonic() < end, "RESPONSE_DEADLINE")
                    chunk = response.read1(min(65536, MAX_JSON + 1 - len(raw)))
                    if not chunk:
                        break
                    raw.extend(chunk)
                    require(len(raw) <= MAX_JSON, "RESPONSE_TOO_LARGE")
                return checked_json(bytes(raw), self.password, session)
        except Failure as error:
            if error.code == "REDIRECT_REJECTED":
                self.count_http(error.status)
            raise
        except urllib.error.HTTPError as error:
            self.count_http(error.code)
            # Never reflect a provider body, exception message, request or credentials.
            error.close()
            raise Failure("HTTP_" + str(error.code), error.code) from None
        except (TimeoutError, socket.timeout):
            raise Failure("HTTP_TIMEOUT") from None
        except (urllib.error.URLError, OSError):
            raise Failure("TRANSPORT_FAILED") from None

    def first_collection(self, response, end):
        buffer = b""
        total = 0
        while time.monotonic() < end:
            chunk = response.read1(4096)
            require(bool(chunk), "SSE_CLOSED_BEFORE_COLLECTION")
            total += len(chunk)
            require(total <= MAX_SSE, "SSE_TOO_LARGE")
            buffer = (buffer + chunk).replace(b"\r\n", b"\n")
            while b"\n\n" in buffer:
                frame, buffer = buffer.split(b"\n\n", 1)
                event, data = b"message", []
                for line in frame.split(b"\n"):
                    field, separator, value = line.partition(b":")
                    if value.startswith(b" "):
                        value = value[1:]
                    if separator and field == b"event":
                        event = value
                    if separator and field == b"data":
                        data.append(value)
                if event == b"collection":
                    return checked_json(b"\n".join(data), self.password)
        raise Failure("SSE_DEADLINE")

    def login(self):
        result = self.request("/session", session=True)
        require(result.get("mode") == "CONNECTED" and result.get("username") == "admin", "SESSION_NOT_CONNECTED_ADMIN")
        require("ADMIN" in result.get("roles", []), "ADMIN_ROLE_MISSING")
        token = result.get("accessToken")
        require(isinstance(token, str) and bool(re.fullmatch(r"[A-Za-z0-9_-]{43}", token)), "INVALID_SESSION_TOKEN")
        self.token = token
        return result.get("organizationId")


def verify_entry(client):
    client.login()
    page = client.request("/devices?limit=100")
    require(page.get("mode") == "CONNECTED", "INVENTORY_NOT_CONNECTED")
    require(page.get("nextCursor") is None, "INVENTORY_UNEXPECTED_NEXT_PAGE")
    devices = page.get("items", [])
    require(len(devices) == 6, "ASSET_COUNT_MISMATCH")
    require(Counter(item.get("managementAddress") for item in devices) == Counter(ADDRESSES), "ASSET_ADDRESS_OR_DUPLICATE_MISMATCH")
    by_address = {item["managementAddress"]: item for item in devices}
    require(len({item.get("id") for item in devices}) == 6, "DUPLICATE_ASSET_ID")
    candidates = client.request("/discovery/candidates?limit=100")
    require(candidates.get("mode") == "CONNECTED" and candidates.get("source") == "DISCOVERY", "DISCOVERY_NOT_CONNECTED")
    require(candidates.get("nextCursor") is None and len(candidates.get("items", [])) == 26, "CANDIDATE_COUNT_MISMATCH")
    linked = [item for item in candidates["items"] if item.get("status") == "LINKED"]
    require(len(linked) == 1, "LINKED_COUNT_MISMATCH")
    require(linked[0].get("address") == "192.168.4.1" and linked[0].get("associatedDeviceId") == by_address[ADDRESSES[0]]["id"], "CANDIDATE_LINK_TARGET_MISMATCH")
    require(sum(bool(item.get("associatedDeviceId")) for item in candidates["items"]) == 1, "ASSOCIATION_COUNT_MISMATCH")
    overview = client.request("/workspace/overview")
    require(overview.get("mode") == "CONNECTED" and overview.get("totals", {}).get("devices") == 6, "OVERVIEW_COUNT_OR_MODE_MISMATCH")
    require(overview.get("recentEventsStatus") == "AVAILABLE", "OVERVIEW_HISTORY_UNAVAILABLE")
    states = Counter()
    numeric_points = []
    for address in ADDRESSES:
        device = by_address[address]
        require(isinstance(device["id"], str) and bool(re.fullmatch(r"[A-Za-z0-9_-]{1,64}", device["id"])), "INVALID_ASSET_ID")
        prefix = "/devices/" + urllib.parse.quote(device["id"], safe="")
        slot, source = ("redfish", "bmc") if address == ADDRESSES[-1] else ("ssh", "ssh")
        slots = collection_items(client.request(prefix + "/collection"))
        matching = [item for item in slots if item["slot"] == slot]
        require(len(matching) == 1 and matching[0].get("enabled") is True, "COLLECTION_NOT_ENABLED")
        connection = matching[0]
        require(connection.get("status") in {"SUCCESS", "PARTIAL", "RUNNING"}, "COLLECTION_NOT_SUCCESSFUL")
        age = (dt.datetime.now(dt.timezone.utc) - instant(connection.get("lastSuccessAt"))).total_seconds()
        require(-5 <= age < 180, "COLLECTION_LAST_SUCCESS_STALE")
        reading = connection.get("lastReading")
        require(isinstance(reading, dict), "SUCCESSFUL_READING_MISSING")
        states[connection["status"]] += 1
        summary = client.request(prefix + "/summary")
        require(any(item.get("sourceId") == source for item in summary.get("sources", [])), "PUBLISHED_SOURCE_MISSING")
        if address in IMANA:
            require(reading.get("identity", {}).get("vendor", "").upper() == "HUAWEI", "IMANA_IDENTITY_MISMATCH")
            end = dt.datetime.now(dt.timezone.utc) - dt.timedelta(seconds=2)
            query = urllib.parse.urlencode({"metric": "temperature_celsius", "from": (end - dt.timedelta(hours=1)).isoformat(), "to": end.isoformat(), "points": 120})
            series = client.request(prefix + "/metrics?" + query)
            require(series.get("metric") == "temperature_celsius", "METRIC_PATH_MISMATCH")
            require("SIMULATED" not in series.get("qualityFlags", []), "SYNTHETIC_METRIC_RESULT")
            points = series.get("points", [])
            require(isinstance(points, list) and len(points) <= 120, "METRIC_POINT_BOUND")
            present = [point for point in points if isinstance(point.get("value"), (int, float)) and not isinstance(point["value"], bool) and math.isfinite(point["value"])]
            require(bool(present), "TEMPERATURE_HISTORY_EMPTY")
            numeric_points.append(len(present))
    first_device = by_address[IMANA[0]]["id"]
    live = client.request("/devices/" + urllib.parse.quote(first_device, safe="") + "/live", stream=True)
    live_items = collection_items(live)
    require(any(item.get("slot") == "ssh" and item.get("enabled") is True for item in live_items), "SSE_EXPECTED_COLLECTION_MISSING")
    return {"assets": len(devices), "candidates": 26, "linkedCandidates": 1, "overviewHistoryAvailable": True,
            "enabledFreshCollections": 6, "collectionStatusCounts": dict(states), "imanaTemperatureSeries": len(numeric_points),
            "imanaNumericPointCounts": numeric_points, "sseCollectionEvents": 1, "sseSlots": len(live_items)}, by_address


LOAD_PATHS = (
    ("devices", "/devices?limit=100"),
    ("monitoring", "/workspace/monitoring?limit=100"),
    ("interfaces", "/workspace/interfaces?limit=100"),
    ("alerts", "/alerts?limit=50"),
    ("topology", "/topology?limit=100"),
    ("workspaceOverview", "/workspace/overview"),
    ("search", "/workspace/search?q=Huawei&limit=10"),
    ("events", "/events?limit=25"),
    ("overview", "/overview"),
    ("collectors", "/collectors"),
)


def load_check(clients):
    def read(index):
        entry = index % 2
        category, path = LOAD_PATHS[(index // 2) % len(LOAD_PATHS)]
        started = time.monotonic()
        code = None
        try:
            result = clients[entry].request(path)
            if "mode" in result:
                require(result["mode"] == "CONNECTED", "LOAD_NOT_CONNECTED")
            if category in {"workspaceOverview", "search"}:
                require(result.get("recentEventsStatus") == "AVAILABLE", "LOAD_HISTORY_UNAVAILABLE")
            if category == "devices":
                require(len(result.get("items", [])) == 6, "LOAD_ASSET_COUNT_MISMATCH")
            elif category in {"monitoring", "interfaces", "alerts", "events", "collectors"}:
                require(isinstance(result.get("items"), list), "LOAD_INVALID_LIST")
            elif category == "topology":
                require(isinstance(result.get("nodes"), list) and isinstance(result.get("edges"), list), "LOAD_INVALID_TOPOLOGY")
        except Failure as error:
            code = error.code
        except Exception:
            code = "UNEXPECTED_RESPONSE_SHAPE"
        return entry, category, (time.monotonic() - started) * 1000, code
    ready = threading.Barrier(16, timeout=30)
    def prepare_worker():
        for client in clients:
            client.prepare_transport()
        # Hold all workers here so every timed read uses an initialized opener.
        ready.wait()

    with ThreadPoolExecutor(max_workers=16, thread_name_prefix="noeriva-read") as pool:
        warmup_started = time.monotonic()
        list(pool.map(lambda _: prepare_worker(), range(16)))
        warmup_ms = (time.monotonic() - warmup_started) * 1000
        load_started = time.monotonic()
        rows = list(pool.map(read, range(160)))
        load_elapsed_ms = (time.monotonic() - load_started) * 1000
    timings = sorted(row[2] for row in rows)
    errors = Counter(row[3] for row in rows if row[3])
    return {"requests": len(rows), "concurrency": 16, "successfulReads": sum(row[3] is None for row in rows),
            "entrypointReadCounts": {str(62 + key): count for key, count in Counter(row[0] for row in rows).items()},
            "routeReadCounts": dict(Counter(row[1] for row in rows)), "errorCounts": dict(errors),
            "transportWarmupMs": round(warmup_ms, 2), "transportWarmupHttpRequests": 0,
            "elapsedMs": round(load_elapsed_ms, 2),
            "latencyScope": "Client wall time: request construction, TCP/HTTP, body read, JSON and assertions; excludes thread/opener initialization and executor queue wait. Not server-only latency; no HTTP warmup or connection pool.",
            "latencyMs": {"min": round(timings[0], 2), "median": round(statistics.median(timings), 2),
                          "p95": round(timings[math.ceil(len(timings) * .95) - 1], 2), "max": round(timings[-1], 2)}}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--outputPath", type=Path, default=ROOT / "docs/implementation/two-node-api-verification.json")
    args = parser.parse_args(argv)
    password = os.environ.get("NOERIVA_BOOTSTRAP_PASSWORD", "")
    report = {"observedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "passed": False, "readOnly": True,
              "scope": "Six-asset deployment acceptance; not a production capacity claim", "entrypoints": {}, "errors": []}
    clients = []
    try:
        require(bool(password), "BOOTSTRAP_PASSWORD_REQUIRED")
        deadline = time.monotonic() + 300
        clients = [Client(base, password, deadline) for base in ENTRIES]
        snapshots = []
        for index, client in enumerate(clients):
            counts, devices = verify_entry(client)
            report["entrypoints"][str(62 + index)] = counts
            snapshots.append({address: device["id"] for address, device in devices.items()})
        require(snapshots[0] == snapshots[1], "ENTRYPOINT_INVENTORY_DIVERGENCE")
        report["load"] = load_check(clients)
        require(report["load"]["successfulReads"] == 160, "LOAD_READ_FAILURES")
        report["passed"] = True
    except Failure as error:
        report["errors"].append({"code": error.code, "httpStatus": error.status})
    except Exception:
        report["errors"].append({"code": "UNEXPECTED_RESPONSE_SHAPE", "httpStatus": 0})
    finally:
        for index, client in enumerate(clients):
            report["entrypoints"].setdefault(str(62 + index), {})["httpStatusCounts"] = dict(client.http)
            client.token = None
            client.password = ""
    try:
        args.outputPath.parent.mkdir(parents=True, exist_ok=True)
        args.outputPath.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    except OSError:
        print(json.dumps({"passed": False, "errors": [{"code": "REPORT_WRITE_FAILED", "httpStatus": 0}]}))
        return 1
    print(json.dumps(report, ensure_ascii=False))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
