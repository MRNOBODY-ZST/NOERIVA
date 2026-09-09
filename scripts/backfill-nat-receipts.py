#!/usr/bin/env python3
"""Backfill one authorized device's NAT receipt index in checkpointed day slices.
Create 005-nat-receipt-index.sql (including its MV) before running this script.
Secrets are read only from NOERIVA_CLICKHOUSE_PASSWORD; no device payload is read.
"""
import argparse
import base64
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import uuid


def instant(value):
    parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if parsed.tzinfo is None:
        raise argparse.ArgumentTypeError('An explicit timezone is required')
    return parsed.astimezone(timezone.utc)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--organization', required=True)
    parser.add_argument('--device', required=True)
    parser.add_argument('--from', dest='start', required=True, type=instant)
    parser.add_argument('--to', dest='end', required=True, type=instant)
    parser.add_argument('--chunk-hours', type=int, choices=range(1, 25), default=24)
    parser.add_argument('--checkpoint', type=Path, required=True)
    args = parser.parse_args()
    if args.start >= args.end:
        parser.error('from must precede to')
    password = os.environ.get('NOERIVA_CLICKHOUSE_PASSWORD')
    if not password:
        parser.error('NOERIVA_CLICKHOUSE_PASSWORD must be set')
    url = os.environ.get('NOERIVA_CLICKHOUSE_URL', 'http://127.0.0.1:8123')
    user = os.environ.get('NOERIVA_CLICKHOUSE_USERNAME', 'noeriva')
    headers = {'Authorization': 'Basic ' + base64.b64encode((user + ':' + password).encode()).decode()}
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    scope = {'url': url, 'organization': args.organization, 'device': args.device}
    state = json.loads(args.checkpoint.read_text()) if args.checkpoint.exists() else {'scope': scope, 'completed': []}
    if state.get('scope') != scope:
        parser.error('Checkpoint belongs to another database or scoped device')
    common = {'param_org': args.organization, 'param_device': args.device, 'max_threads': 2,
              'max_memory_usage': 268435456, 'max_rows_to_read': 10000000,
              'max_bytes_to_read': 1073741824, 'max_execution_time': 60,
              'read_overflow_mode': 'throw', 'timeout_overflow_mode': 'throw', 'wait_end_of_query': 1, 'async_insert': 0}

    def request(sql, parameters):
        uri = url.rstrip('/') + '/?' + urllib.parse.urlencode(parameters)
        with opener.open(urllib.request.Request(uri, sql.encode(), headers), timeout=70) as response:
            result = response.read(8192).decode()
            if result.strip():
                raise RuntimeError('ClickHouse did not confirm an empty INSERT acknowledgement')

    current = args.start
    while current < args.end:
        end = min(current + timedelta(hours=args.chunk_hours), args.end)
        window = [current.isoformat(), end.isoformat()]
        if window not in state['completed']:
            query_id = 'noeriva-nat-backfill-' + str(uuid.uuid4())
            sql = """INSERT INTO noeriva.nat_audit_receipts
SELECT organization_id,device_id,received_at,event_id,exported_at,private_ip,public_ip,protocol
FROM noeriva.nat_audit_events
WHERE organization_id={org:String} AND device_id={device:String}
 AND exported_at>=parseDateTime64BestEffort({from:String})
 AND exported_at<parseDateTime64BestEffort({to:String})"""
            try:
                request(sql, {**common, 'query_id': query_id, 'param_from': window[0], 'param_to': window[1],
                              'max_insert_threads': 1, 'max_block_size': 16384})
            except Exception:
                try:
                    request('KILL QUERY WHERE query_id={id:String} ASYNC',
                            {'param_id': query_id, 'max_execution_time': 2})
                except Exception:
                    pass
                print(json.dumps({'completedSlices': len(state['completed']), 'failedSlice': window,
                                  'action': 'Retry this checkpoint; use smaller chunk-hours if its read/time budget was exceeded. A partially written slice is safe to repeat.'}))
                raise
            state['completed'].append(window)
            state.setdefault('queries', []).append({'slice': window, 'queryId': query_id})
            args.checkpoint.parent.mkdir(parents=True, exist_ok=True)
            temp = args.checkpoint.with_suffix(args.checkpoint.suffix + '.tmp')
            temp.write_text(json.dumps(state, indent=2) + '\n')
            temp.replace(args.checkpoint)
            print(json.dumps({'completedSlice': window, 'completedSlices': len(state['completed'])}))
        current = end


if __name__ == '__main__':
    main()
