#!/usr/bin/env python3
"""Export an explicit six-device migration, never a whole-stack backup.

No source writes, remote hosts, .env loading, shell commands or HTTP proxies.
Secrets must be supplied by the operator in the process environment.
Run --self-test without any database, HTTP or credentials.
"""
import argparse
import base64
import datetime as dt
import hashlib
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
import urllib.parse
import urllib.request
import uuid

ORG = 'default'
MAX_BYTES = 256 * 1024 * 1024
MAX_ROWS = 100000
MOCK = re.compile(r'(?i)(?:\bmock\b|synthetic|benchmark|load[-_ ]?test|压测|模拟)')


class ExportError(Exception):
    """Only constant safe error codes may be exposed to stderr."""


def require(condition, code):
    if not condition:
        raise ExportError(code)


def identifier(value):
    require(isinstance(value, str) and re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', value), 'INVALID_ID')
    require(str(uuid.UUID(value)) == value, 'INVALID_ID')
    return value


def id_list(value, count=None):
    require(isinstance(value, list) and 1 <= len(value) <= 100, 'INVALID_ID_LIST')
    result = [identifier(v) for v in value]
    require(len(set(result)) == len(result) and (count is None or len(result) == count), 'INVALID_ID_COUNT')
    return result


def assets_input(value):
    require(isinstance(value, list) and len(value) == 6 and all(isinstance(v, dict) for v in value), 'INVALID_ASSETS')
    ids = id_list([v.get('id') for v in value], 6)
    hosts = []
    for v in value:
        require(v.get('slot') in ('ssh', 'redfish', 'snmp'), 'INVALID_SLOT')
        try:
            host = str(ipaddress.IPv4Address(v.get('host')))
        except (ValueError, TypeError):
            raise ExportError('INVALID_HOST') from None
        require(host == v['host'] and ipaddress.ip_address(host).is_private, 'INVALID_HOST')
        hosts.append(host)
    require(len(set(hosts)) == 6, 'DUPLICATE_HOST')
    return {i: v for i, v in zip(ids, value)}


def sql_ids(values):
    return ','.join("'" + identifier(v) + "'" for v in values)


def json_field(value):
    return json.loads(value) if isinstance(value, str) else value


def validate_discovery(candidates, runs, selected, expected_candidates, expected_runs):
    require({r['id'] for r in candidates} == set(expected_candidates), 'CANDIDATE_SET_MISMATCH')
    require({r['id'] for r in runs} == set(expected_runs), 'RUN_SET_MISMATCH')
    links = 0
    for row in candidates:
        require(row['organization_id'] == ORG and row['site_id'] == 'default', 'CANDIDATE_SCOPE_MISMATCH')
        c = json_field(row['payload'])
        require(c['id'] == row['id'] and c['siteId'] == row['site_id'] and c['address'] == row['address'], 'CANDIDATE_PAYLOAD_MISMATCH')
        require(c.get('associatedDeviceId') == row['associated_device_id'], 'CANDIDATE_ASSOCIATION_MISMATCH')
        association = row['associated_device_id']
        require(association is None or association in selected, 'EXTERNAL_ASSOCIATION')
        links += association is not None
        evidence = c.get('evidence')
        require(isinstance(evidence, list) and 1 <= len(evidence) <= 100, 'INVALID_CANDIDATE_EVIDENCE')
        require(all(e.get('sourceDeviceId') is None or e.get('sourceDeviceId') in selected for e in evidence), 'EXTERNAL_EVIDENCE_SOURCE')
    require(links == 1, 'ASSOCIATION_COUNT_MISMATCH')
    for row in runs:
        require(row['organization_id'] == ORG and row['site_id'] == 'default', 'RUN_SCOPE_MISMATCH')
        result = json_field(row['result'])
        require(result.get('id') == row['id'] and result.get('siteId') == 'default', 'RUN_PAYLOAD_MISMATCH')
        sources = result.get('sources')
        require(isinstance(sources, list) and 1 <= len(sources) <= 8, 'INVALID_RUN_SOURCES')
        require(all(s.get('deviceId') is None or s.get('deviceId') in selected for s in sources), 'EXTERNAL_RUN_SOURCE')


class Mysql:
    def __init__(self, password, folder):
        self.folder = folder
        self.env = {k: v for k, v in os.environ.items() if not k.lower().endswith('_proxy') and not k.startswith('NOERIVA_')}
        self.env['MYSQL_PWD'] = password
        self.prefix = ['docker', 'exec', '-e', 'MYSQL_PWD', 'noeriva-mysql-1']

    def run(self, args, output, timeout=120):
        try:
            p = subprocess.run(self.prefix + args, env=self.env, stdout=output, stderr=subprocess.DEVNULL, timeout=timeout, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise ExportError('MYSQL_COMMAND_FAILED') from None
        require(p.returncode == 0, 'MYSQL_COMMAND_FAILED')

    def lines(self, query):
        with tempfile.TemporaryFile(dir=self.folder) as output:
            self.run(['mysql', '--user=root', '--database=noeriva', '--batch', '--raw', '--skip-column-names', "--init-command=SET time_zone='+00:00'", '--execute', query], output)
            require(output.tell() <= MAX_BYTES, 'MYSQL_RESULT_TOO_LARGE')
            output.seek(0)
            result = [line.decode().rstrip('\n') for line in output]
        require(len(result) <= MAX_ROWS, 'MYSQL_TOO_MANY_ROWS')
        return result

    def rows(self, table, where):
        require(re.fullmatch(r'[a-z_]+', table), 'INVALID_TABLE')
        columns = self.lines("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='noeriva' AND TABLE_NAME='" + table + "' ORDER BY ORDINAL_POSITION")
        require(columns and all(re.fullmatch(r'[a-z_]+', c) for c in columns), 'INVALID_COLUMNS')
        fields = ','.join("'" + c + "',`" + c + '`' for c in columns)
        return [json.loads(s) for s in self.lines('SELECT /*+ MAX_EXECUTION_TIME(10000) */ JSON_OBJECT(' + fields + ') FROM `' + table + '` WHERE ' + where)]

    def count(self, table, where):
        return int(self.lines('SELECT /*+ MAX_EXECUTION_TIME(10000) */ COUNT(*) FROM `' + table + '` WHERE ' + where)[0])

    def dump(self, table, where, path, expected):
        with tempfile.TemporaryFile(dir=self.folder) as raw:
            self.run(['mysqldump', '--user=root', '--single-transaction', '--quick', '--compact', '--complete-insert', '--skip-extended-insert', '--no-create-info', '--skip-triggers', '--set-gtid-purged=OFF', '--no-tablespaces', '--hex-blob', '--tz-utc', '--where=' + where, 'noeriva', table], raw)
            require(raw.tell() <= MAX_BYTES, 'DUMP_TOO_LARGE')
            raw.seek(0)
            prefix = ('INSERT INTO `' + table + '` (').encode()
            count = 0
            with private_file(path) as target:
                for line in raw:
                    require(len(line) <= 4 * 1024 * 1024, 'DUMP_ROW_TOO_LARGE')
                    if line.startswith(prefix):
                        target.write(line)
                        count += 1
                    else:
                        require(not line.strip() or line.startswith((b'/*', b'--', b'SET ')), 'UNEXPECTED_DUMP_STATEMENT')
            require(count == expected, 'DUMP_COUNT_MISMATCH')


def private_file(path):
    return os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb')


def fingerprint(rows):
    parts = sorted(hashlib.sha256(json.dumps(r, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).digest() for r in rows)
    return hashlib.sha256(b''.join(parts)).hexdigest()


def file_metadata(path, rows, samples=None, window=None):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for part in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(part)
    result = {'rows': rows, 'bytes': path.stat().st_size, 'sha256': digest.hexdigest()}
    if samples is not None:
        result['samples'] = samples
    if window:
        result['timeWindow'] = window
    return result


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise ExportError('HTTP_REDIRECT_REJECTED')


def http_jsonl(url, body, headers, path, validate):
    # No environment proxy, redirects, target-host override or managed-device access.
    parsed = urllib.parse.urlsplit(url)
    require(parsed.scheme == 'http' and parsed.hostname == '127.0.0.1' and parsed.port in (18428, 18123), 'INVALID_EXPORT_ENDPOINT')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    req = urllib.request.Request(url, data=body, headers=headers)
    rows, samples, total = 0, 0, 0
    times = []
    try:
        with opener.open(req, timeout=45) as response, private_file(path) as output:
            require(response.status == 200, 'HTTP_EXPORT_FAILED')
            while True:
                line = response.readline(4 * 1024 * 1024 + 1)
                if not line:
                    break
                total += len(line)
                require(len(line) <= 4 * 1024 * 1024 and total <= MAX_BYTES, 'HTTP_EXPORT_TOO_LARGE')
                if not line.strip():
                    continue
                record = json.loads(line)
                n, earliest, latest = validate(record)
                samples += n
                if earliest is not None:
                    times.extend((earliest, latest))
                output.write(line.rstrip(b'\r\n') + b'\n')
                rows += 1
                require(rows <= MAX_ROWS, 'HTTP_TOO_MANY_ROWS')
    except ExportError:
        raise
    except Exception:
        raise ExportError('HTTP_EXPORT_FAILED') from None
    return file_metadata(path, rows, samples, {'from': min(times), 'to': max(times)} if times else None)


def vm_validator(selected):
    def validate(row):
        labels = row.get('metric', {})
        require(labels.get('organization_id') == ORG and labels.get('device_id') in selected, 'VM_SCOPE_MISMATCH')
        require(str(labels.get('__name__', '')).startswith('noeriva_') and not any(MOCK.search(str(v)) for v in labels.values()), 'VM_MOCK_OR_UNKNOWN_SERIES')
        values, times = row.get('values'), row.get('timestamps')
        require(isinstance(values, list) and isinstance(times, list) and len(values) == len(times) and len(times) > 0, 'VM_INVALID_SAMPLES')
        require(all(isinstance(v, (float, int)) and not isinstance(v, bool) and math.isfinite(v) for v in values), 'VM_INVALID_VALUE')
        require(all(isinstance(t, int) and not isinstance(t, bool) and t >= 0 for t in times), 'VM_INVALID_TIME')
        return len(times), min(times), max(times)
    return validate


def ch_validator(selected):
    def validate(row):
        require(row.get('organization_id') == ORG and row.get('device_id') in selected, 'CH_SCOPE_MISMATCH')
        require(not MOCK.search(str(row.get('message', '')) + ' ' + str(row.get('source', ''))), 'CH_MOCK_EVENT')
        at = row.get('observed_at')
        require(isinstance(at, str) and len(at) <= 40, 'CH_INVALID_TIME')
        return 1, at, at
    return validate


def export(args):
    selected = assets_input(json.loads(args.assets.read_text()))
    candidate_ids = id_list(json.loads(args.candidate_ids.read_text()), 26)
    run_ids = id_list(json.loads(args.run_ids.read_text()))
    require(not args.output.exists(), 'OUTPUT_MUST_NOT_EXIST')
    password = os.environ.get('NOERIVA_DB_ROOT_PASSWORD')
    ch_password = os.environ.get('NOERIVA_CLICKHOUSE_PASSWORD')
    require(password and ch_password, 'MISSING_RUNTIME_CREDENTIALS')
    args.output.mkdir(mode=0o700, parents=False)
    os.chmod(args.output, 0o700)
    db = Mysql(password, args.output)
    scope = "organization_id='default' AND "
    devices = sql_ids(selected)
    candidates = sql_ids(candidate_ids)
    runs = sql_ids(run_ids)
    device_where = scope + 'id IN (' + devices + ')'
    child_where = scope + 'device_id IN (' + devices + ')'
    relations = devices + ',' + candidates + ',' + runs
    audit_where = scope + '(resource_id IN (' + relations + ') OR resource_id IN (SELECT CONCAT(device_id,\'/\',slot) FROM device_connection WHERE ' + child_where + ') OR resource_id IN (SELECT id FROM network_interface WHERE ' + child_where + ') OR resource_id IN (SELECT id FROM alert WHERE ' + child_where + '))'
    tables = [('device', device_where), ('device_current', child_where), ('source_current', child_where), ('device_connection', child_where), ('device_metric_binding', child_where), ('network_interface', child_where), ('alert', child_where), ('discovery_candidate', scope + 'id IN (' + candidates + ')'), ('discovery_run', scope + 'id IN (' + runs + ')'), ('discovery_mac_claim', scope + 'candidate_id IN (' + candidates + ')'), ('control_audit', audit_where)]
    before = {t: db.rows(t, w) for t, w in tables}
    require(len(before['device']) == 6 and {r['id'] for r in before['device']} == set(selected), 'DEVICE_SET_MISMATCH')
    for row in before['device']:
        require(row['site_id'] == 'default' and row['management_address'] == selected[row['id']]['host'], 'DEVICE_SCOPE_MISMATCH')
        require(not MOCK.search(row['name']), 'MOCK_DEVICE_REJECTED')
    require(len(before['device_connection']) == 6, 'CONNECTION_COUNT_MISMATCH')
    for row in before['device_connection']:
        require(not row['enabled'] and row['status'] != 'RUNNING' and not row['lease_token'] and row['lease_until'] is None, 'CONNECTION_NOT_QUIESCENT')
        require(row['slot'] == selected[row['device_id']]['slot'] and row['ciphertext'] and row['source_epoch'], 'CONNECTION_IDENTITY_MISMATCH')
        settings = json_field(row['settings'])
        require(settings.get('host') == selected[row['device_id']]['host'], 'CONNECTION_HOST_MISMATCH')
    validate_discovery(before['discovery_candidate'], before['discovery_run'], selected, candidate_ids, run_ids)
    require(all(r['site_id'] == 'default' for r in before['discovery_mac_claim']), 'MAC_CLAIM_SCOPE_MISMATCH')
    require(db.count('outbox', scope + 'aggregate_id IN (' + devices + ") AND published_at IS NULL") == 0, 'PENDING_SELECTED_OUTBOX')
    for table in ('rollup_progress', 'rollup_job', 'workbench_record', 'workbench_network_evidence'):
        require(db.count(table, child_where) == 0, 'EXCLUDED_RELATED_STATE_REQUIRES_REVIEW')
    require(db.count('topology_edge', scope + '(source_id IN (' + devices + ') OR target_id IN (' + devices + '))') == 0, 'EXCLUDED_TOPOLOGY_REQUIRES_REVIEW')
    started = dt.datetime.now(dt.timezone.utc).isoformat()
    files = {}
    for number, (table, where) in enumerate(tables, 1):
        path = args.output / f'{number:02d}-{table}.sql'
        db.dump(table, where, path, len(before[table]))
        files[path.name] = file_metadata(path, len(before[table]))
    matcher = '{__name__=~"noeriva_.*",organization_id="default",device_id=~"(?:' + '|'.join(selected) + ')"}'
    vm_body = urllib.parse.urlencode({'match[]': matcher, 'reduce_mem_usage': '1'}).encode()
    files['metrics.jsonl'] = http_jsonl('http://127.0.0.1:18428/api/v1/export', vm_body, {'Content-Type': 'application/x-www-form-urlencoded'}, args.output / 'metrics.jsonl', vm_validator(selected))
    query = 'SELECT * FROM noeriva.control_events FINAL WHERE organization_id=\'default\' AND device_id IN (' + devices + ') ORDER BY organization_id,device_id,observed_at,event_id FORMAT JSONEachRow'
    params = urllib.parse.urlencode({'database': 'noeriva', 'max_execution_time': 30, 'max_threads': 2, 'max_memory_usage': 268435456, 'max_result_bytes': MAX_BYTES, 'result_overflow_mode': 'throw', 'wait_end_of_query': 1})
    auth = base64.b64encode(('noeriva:' + ch_password).encode()).decode()
    files['events.jsonl'] = http_jsonl('http://127.0.0.1:18123/?' + params, query.encode(), {'Authorization': 'Basic ' + auth}, args.output / 'events.jsonl', ch_validator(selected))
    for table, where in tables:
        require(fingerprint(db.rows(table, where)) == fingerprint(before[table]), 'SOURCE_CHANGED_DURING_EXPORT')
    manifest = {'startedAt': started, 'completedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'counts': {'devices': 6, 'connections': 6, 'candidates': 26, 'associations': 1, 'runs': len(run_ids)}, 'files': files}
    with private_file(args.output / 'manifest.json') as output:
        output.write((json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode())
    print(json.dumps({'exportedDevices': 6, 'exportedCandidates': 26, 'exportedFiles': len(files)}))


class InputTests(unittest.TestCase):
    def test_accepts_only_canonical_ids(self):
        self.assertEqual(identifier('00000000-0000-4000-8000-000000000001'), '00000000-0000-4000-8000-000000000001')
        for bad in (None, '', "a' OR 1=1 --", 'x; DROP TABLE device', '../path', 'Ａ' * 36, '00000000-0000-4000-8000-00000000000A'):
            with self.subTest(value=bad), self.assertRaises(ExportError):
                identifier(bad)

    def test_rejects_duplicate_ids_and_wrong_counts(self):
        key = '00000000-0000-4000-8000-000000000001'
        for value in ([key, key], [], [key]):
            with self.assertRaises(ExportError):
                id_list(value, 6)

    def test_rejects_external_or_mock_history(self):
        selected = {'00000000-0000-4000-8000-000000000001'}
        for row in ({'organization_id': 'other', 'device_id': next(iter(selected))}, {'organization_id': 'default', 'device_id': 'external'}):
            with self.assertRaises(ExportError):
                ch_validator(selected)(row)
        with self.assertRaises(ExportError):
            ch_validator(selected)({'organization_id': 'default', 'device_id': next(iter(selected)), 'message': 'synthetic fixture'})

    def test_rejects_external_discovery_sources(self):
        key = '00000000-0000-4000-8000-000000000001'
        row = {'organization_id': 'default', 'site_id': 'default', 'id': key, 'address': '192.0.2.1', 'associated_device_id': key, 'payload': {'id': key, 'siteId': 'default', 'address': '192.0.2.1', 'associatedDeviceId': key, 'evidence': [{'sourceDeviceId': 'external'}]}}
        with self.assertRaises(ExportError):
            validate_discovery([row], [], {key}, [key], [])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('--assets', type=Path)
    parser.add_argument('--candidate-ids', type=Path)
    parser.add_argument('--run-ids', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.self_test:
        result = unittest.TextTestRunner(verbosity=1).run(unittest.defaultTestLoader.loadTestsFromTestCase(InputTests))
        raise SystemExit(0 if result.wasSuccessful() else 1)
    if not all((args.assets, args.candidate_ids, args.run_ids, args.output)):
        parser.error('--assets, --candidate-ids, --run-ids and --output are required')
    try:
        export(args)
    except ExportError as error:
        raise SystemExit(str(error)) from None
    except Exception:
        # Never include subprocess output, URLs, credentials, SQL or row content.
        raise SystemExit('EXPORT_FAILED_NO_COMPLETE_MANIFEST') from None


if __name__ == '__main__':
    main()
