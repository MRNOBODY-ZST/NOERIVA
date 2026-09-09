"""Typed Clarity workspace and workbench extensions to the checked-in API contract."""


def extend(schemas, endpoint, helpers):
    S, N, I, R, A, O = (helpers[name] for name in ['S', 'N', 'I', 'R', 'A', 'O'])
    T, NULL_T, NULL_S, NULL_N, B, MODE = (helpers[name] for name in ['T', 'NULL_T', 'NULL_S', 'NULL_N', 'B', 'MODE'])
    param, page = helpers['param'], helpers['page']
    identifier = S(minLength=1, maxLength=64)
    title = S(minLength=1, maxLength=200, pattern=r'\S')
    source = S(minLength=1, maxLength=120, pattern=r'\S')
    provenance = S(enum=['MANUAL', 'SYNTHETIC'], description='Import provenance; does not establish verification against a real device.')
    revision = I(format='int64', minimum=1)
    sha256 = S(pattern='^[0-9a-f]{64}$')
    observed = {**T, 'description': 'At least 2000-01-01T00:00:00Z and no later than server now +120 seconds.'}
    nullable_ref = lambda name: {'anyOf': [R(name), {'type': 'null'}]}
    nullable_port = {'type': ['integer', 'null'], 'minimum': 1, 'maximum': 65535}
    caller_id = S(pattern='^[A-Za-z0-9_-]{1,128}$')
    bounded_text = lambda length: {**NULL_S, 'maxLength': length}
    health = S(enum=helpers['HEALTH'])
    history_status = S(enum=['AVAILABLE', 'UNAVAILABLE'])
    schemas.update({
        'MonitoringSource': O({'deviceId':identifier, 'deviceName':S(), 'deviceType':S(enum=['HOST','BMC','ROUTER','SWITCH','FIREWALL']),
            'siteId':identifier, 'siteName':S(), 'sourceId':S(enum=helpers['SOURCES']), 'kind':S(enum=['DeviceSummaryObserved','SourceHealthChanged']),
            'health':health, 'observedAt':T, 'freshness':S(enum=['FRESH','STALE']), 'metrics':helpers['METRIC_VALUES'], 'sequence':I(format='int64',minimum=0,maximum=9223372036854775806), 'epoch':S()}),
        'WorkspaceInterface': O({'id':identifier, 'deviceId':identifier, 'deviceName':S(), 'siteId':identifier, 'siteName':S(), 'name':S(),
            'macAddress':NULL_S, 'speedBps':S(pattern='^[0-9]{1,20}$',description='UInt64 decimal string, at most 18446744073709551615.'),
            'adminStatus':S(), 'operStatus':S(), 'deviceLastSeen':NULL_T, 'deviceFreshness':helpers['FRESHNESS']}),
        'SiteHealth': O({'siteId':identifier, 'siteName':S(), 'timezone':NULL_S,
            **{name:I(format='int64',minimum=0) for name in ['devices','critical','warning','healthy','unknown','stale']}}),
        'WorkspaceOverview': O({'totals':R('Overview'), 'siteHealth':{**A(R('SiteHealth')),'maxItems':1000}, 'priorityDevices':{**A(R('Device')),'maxItems':10},
            'trafficSource':nullable_ref('MonitoringSource'), 'recentEvents':{**A(R('Event')),'maxItems':5},
            'recentEventsStatus':S(enum=['AVAILABLE','UNAVAILABLE','NOT_REQUESTED']), 'asOf':T, 'mode':MODE, 'qualityFlags':A(S())}),
        'WorkspaceSearch': O({'assets':{**A(R('Device')),'maxItems':20}, 'recentEvents':{**A(R('Event')),'maxItems':20}, 'query':S(minLength=2,maxLength=120),
            'eventScanLimit':I(enum=[100]), 'eventWindowFrom':T, 'eventWindowTo':T, 'eventScope':S(enum=['RECENT_7_DAYS_MAX_100']),
            'recentEventsStatus':history_status, 'asOf':T, 'mode':MODE, 'qualityFlags':A(S())}),
        'IncidentInput': O({'title':title, 'severity':S(enum=['INFO','WARNING','CRITICAL']), 'deviceId':identifier,
            'alertId':bounded_text(128), 'assignee':{**bounded_text(120),'description':'Optional enabled account in the authenticated organization.'}, 'note':bounded_text(2000)}, ['title','severity','deviceId']),
        'IncidentUpdate': O({'revision':revision, 'title':title, 'status':S(enum=['OPEN','INVESTIGATING','RESOLVED']), 'assignee':bounded_text(120),
            'note':{**bounded_text(2000),'description':'A nonblank note appends to the immutable note list. Resolving requires a nonblank resolution note.'}}, ['revision','title','status']),
        'Note': O({'id':identifier,'author':S(),'text':S(maxLength=2000),'createdAt':T}),
        'Incident': O({'id':identifier,'title':title,'severity':S(enum=['INFO','WARNING','CRITICAL']),'status':S(enum=['OPEN','INVESTIGATING','RESOLVED']),
            'deviceId':identifier,'alertId':bounded_text(128),'assignee':bounded_text(120),'createdBy':S(),'createdAt':T,'updatedAt':T,'revision':revision,'notes':{**A(R('Note')),'maxItems':100}}),
        'EvidenceInput': O({'deviceId':identifier,'title':title,'kind':S(enum=['NOTE','EVENT','OBSERVATION','NAT','LEASE','CONFIGURATION','CHECK']),
            'source':source,'observedAt':observed,'content':S(minLength=1,maxLength=32768,pattern=r'\S',description='Plain text; also limited to 65536 UTF-8 bytes. Not executed. SHA-256 covers these UTF-8 bytes exactly.'),'provenance':provenance}),
        'ConfigurationInput': O({'deviceId':identifier,'title':title,'source':source,'capturedAt':observed,
            'content':S(minLength=1,maxLength=65536,pattern=r'\S',description='Also at most 131072 UTF-8 bytes and 2000 lines. Credential-bearing lines and private-key blocks are redacted before storage/checksum. Never pushed to a device.'),'provenance':provenance}),
        'DiffLine': O({'kind':S(enum=['CONTEXT','ADDED','REMOVED']),'beforeLine':{'type':['integer','null'],'minimum':1,'maximum':2000},
            'afterLine':{'type':['integer','null'],'minimum':1,'maximum':2000},'text':S()}),
        'Diff': O({'deviceId':identifier,'beforeId':identifier,'afterId':identifier,'added':I(minimum=0,maximum=2000),'removed':I(minimum=0,maximum=2000),
            'unchanged':I(minimum=0,maximum=2000),'lines':{**A(R('DiffLine')),'maxItems':4000},'asOf':T}),
        'CheckInput': O({'deviceId':identifier,'name':title,'type':S(enum=['TCP','HTTP','HTTPS','DNS','TLS']),
            'target':S(minLength=1,maxLength=253,description='Read-only probe target: TCP requires host:port (brackets for IPv6); TLS accepts host:port, default 443; HTTP/HTTPS accept matching URLs without credentials or fragments; DNS accepts a hostname. No whitespace/control characters. MANUAL enabled definitions are scheduled by the native worker; TargetPolicy validates all resolved addresses before a socket uses the selected literal address.'),
            'intervalSeconds':I(minimum=30,maximum=86400),'enabled':{**B,'default':False},'provenance':provenance}, ['deviceId','name','type','target','intervalSeconds','provenance']),
        'CheckUpdate': O({'revision':revision,'name':title,'target':S(minLength=1,maxLength=253,description='No whitespace or control characters.'),
            'intervalSeconds':I(minimum=30,maximum=86400),'enabled':{**B,'default':False}}, ['revision','name','target','intervalSeconds']),
        'Revision': O({'revision':revision}),
        'CheckResultInput': O({'id':caller_id,'checkId':identifier,'observedAt':observed,'status':S(enum=['PASS','FAIL','UNKNOWN']),
            'latencyMs':{'type':['number','null'],'minimum':0,'maximum':600000,'description':'PASS requires a finite latency; optional for FAIL/UNKNOWN.'},
            'message':bounded_text(1000),'source':source,'provenance':provenance,
            'definitionRevision':{'type':['integer','null'],'format':'int64','minimum':1,'default':1,'description':'Must equal the current check definition revision. Omitted/null means revision 1, not the latest revision.'}}, ['id','checkId','observedAt','status','source','provenance']),
        'NetworkInput': O({'id':caller_id,'deviceId':identifier,'kind':S(enum=['NAT','ADDRESS_LEASE']),
            'privateIp':S(minLength=1,maxLength=45,description='IPv4 or IPv6 literal. Hostnames are rejected without DNS lookup.'), 'privatePort':nullable_port,
            'publicIp':{**bounded_text(45),'description':'NAT requires an IPv4/IPv6 literal. ADDRESS_LEASE requires null/absent.'}, 'publicPort':nullable_port,
            'protocol':{'type':['string','null'],'enum':['TCP','UDP',None]}, 'validFrom':observed,
            'validTo':{**T,'description':'Exclusive end, after validFrom; interval <=31 days and at most server now +31 days.'},
            'lifecycle':S(enum=['COMPLETE','SNAPSHOT_ONLY']),'clockUncertaintyMs':I(minimum=0,maximum=300000,default=0),'source':source,'provenance':provenance},
            ['id','deviceId','kind','privateIp','validFrom','validTo','lifecycle','source','provenance']),
        'InvestigationInput': O({'ip':S(minLength=1,maxLength=45,description='IPv4/IPv6 literal; normalized without DNS.'),'port':I(minimum=1,maximum=65535),
            'protocol':S(enum=['TCP','UDP']),'at':observed,'direction':S(enum=['PUBLIC_TO_PRIVATE','PRIVATE_TO_PUBLIC'])}),
        'Candidate': O({'nat':R('NetworkEvidence'),'leases':{**A(R('NetworkEvidence')),'maxItems':100},'qualityFlags':A(S())}),
        'Investigation': O({'id':identifier,'query':R('InvestigationInput'),'status':S(enum=['CONFIRMED','AMBIGUOUS','INSUFFICIENT_EVIDENCE','NO_MATCH']),
            'candidates':{**A(R('Candidate')),'maxItems':100},'qualityFlags':A(S()),'asOf':T,'mode':MODE}),
        'Audit': O({'id':identifier,'actor':S(),'action':S(),'resourceId':S(maxLength=128),'createdAt':T}),
    })
    schemas['IncidentSummary'] = O({**{name:value for name,value in schemas['Incident']['properties'].items() if name != 'notes'}, 'noteCount':I(minimum=0,maximum=100)})
    evidence = {'id':identifier,'deviceId':identifier,'title':title,'kind':S(enum=['NOTE','EVENT','OBSERVATION','NAT','LEASE','CONFIGURATION','CHECK']),
        'source':source,'observedAt':T,'provenance':provenance,'sha256':sha256,'integrity':S(enum=['UNSIGNED']),'createdBy':S(),'createdAt':T}
    schemas['EvidenceMetadata'] = O(evidence)
    schemas['Evidence'] = O({**evidence,'content':S(maxLength=32768)})
    schemas['Manifest'] = O({'version':I(enum=[1]),'algorithm':S(enum=['SHA-256']),'sha256':sha256,'integrity':S(enum=['UNSIGNED']),'worm':{'type':'boolean','const':False},'evidence':R('Evidence')})
    snapshot = {'id':identifier,'deviceId':identifier,'title':title,'source':source,'capturedAt':T,'provenance':provenance,
        'sha256':sha256,'redactedLines':I(minimum=0,maximum=2000),'createdBy':S(),'createdAt':T}
    schemas['SnapshotMetadata'] = O(snapshot)
    schemas['Snapshot'] = O({**snapshot,'content':S(description='Server-redacted content with normalized line endings. The digest covers this stored text.')})
    schemas['CheckResult'] = O({**schemas['CheckResultInput']['properties'],'message':S(maxLength=1000),'receivedAt':T,'definitionRevision':revision})
    schemas['CheckResultInput']['allOf'] = [{'if':{'properties':{'status':{'const':'PASS'}}},'then':{'required':['latencyMs'],'properties':{'latencyMs':N(minimum=0,maximum=600000)}}}]
    schemas['Check'] = O({'id':identifier,'deviceId':identifier,'name':title,'type':S(enum=['TCP','HTTP','HTTPS','DNS','TLS']),
        'target':S(minLength=1,maxLength=253),'intervalSeconds':I(minimum=30,maximum=86400),'enabled':B,'archived':B,'provenance':provenance,
        'execution':S(enum=['COLLECTOR_REPORTED','NATIVE_WORKER'],description='MANUAL definitions execute through the bounded native worker; SYNTHETIC definitions only accept external fixture reports.'),'revision':revision,'createdBy':S(),'createdAt':T,'updatedAt':T,'lastResult':nullable_ref('CheckResult')})
    schemas['NetworkEvidence'] = O({**schemas['NetworkInput']['properties'],
        'deviceId':{**identifier,'description':'For NAT, the observing gateway; for ADDRESS_LEASE, the lease subject.'},
        'siteId':{**identifier,'description':'Copied server-side from device inventory at import. Never supplied by the caller; later inventory changes do not rewrite this scope.'},'receivedAt':T})
    schemas['NetworkInput']['allOf'] = [{'if':{'properties':{'kind':{'const':'NAT'}}},'then':{'required':['privatePort','publicIp','publicPort','protocol'],
        'properties':{'privatePort':I(minimum=1,maximum=65535),'publicPort':I(minimum=1,maximum=65535),'publicIp':S(minLength=1,maxLength=45),'protocol':S(enum=['TCP','UDP'])}},
        'else':{'properties':{name:{'type':'null'} for name in ['privatePort','publicIp','publicPort','protocol']}}}]

    workspace_filters = [param('limit',I(minimum=1,maximum=100,default=50)),param('q',S(maxLength=120,default='')),
        param('siteId',S(maxLength=64,default='')),param('deviceId',S(maxLength=64,default=''))]
    endpoint('/workspace/monitoring','get','Keyset page of latest source snapshots with independent freshness',page('MonitoringSource'),
        parameters=workspace_filters+[param('cursor',S(maxLength=256,default='')),param('freshness',S(enum=['','FRESH','STALE'],default=''))],
        description='Page unit is a source, not an individual sensor. One bounded MySQL join returns the metrics map without per-device VM requests. Device/source IDs order ascending. q is a literal prefix of device name/address or source ID. The response asOf anchors the 180-second freshness boundary. No-source assets produce no fabricated sensor row. Source is MYSQL_CURRENT or SIMULATED.')
    endpoint('/workspace/interfaces','get','Joined global interface directory with device freshness',page('WorkspaceInterface'),
        parameters=workspace_filters+[param('cursor',S(maxLength=4096,default=''))],
        description='Case-folded interface name ascending, then ID ascending keyset page; digits follow alphabetical (lexical) ordering. The opaque i1 cursor binds organization and q/site/device filters, which cannot change between pages. Names use an automatically indexed lowercase 512-character prefix; names longer than that tie by ID. Existing UUID-only cursors must restart from the first page. No client-side page reordering is needed. q is a literal prefix of device name/address or interface name. deviceLastSeen/deviceFreshness describe the device checkpoint, not an interface-specific observation. Unknown admin/oper states remain UNKNOWN. Source is MYSQL_CURRENT or SIMULATED.')
    endpoint('/workspace/overview','get','Exact scoped counts, priority assets and one observed traffic source',R('WorkspaceOverview'),
        parameters=[param('siteId',S(maxLength=64,default=''))],
        description='Counts cover the full authorized site scope, not only a fetched device page. Here totals.stale and siteHealth.stale count previously observed assets with no source checkpoint fresh within 180 seconds; never-observed assets are missing, not stale. The /overview endpoint uses the same definition. Site directory max1000, priority CRITICAL/WARNING/UNKNOWN devices max10. trafficSource is the latest bandwidth-bearing source in scope and represents one device, not an aggregated site total. Unscoped recentEvents covers seven days/max5 and has explicit UNAVAILABLE on history failure. A site-filtered query returns recentEventsStatus=NOT_REQUESTED and an empty event list. Global source timestamps may change between the bounded aggregate queries; this is not an atomic historical snapshot.')
    endpoint('/workspace/search','get','Bounded asset prefix and recent event search with coverage labels',R('WorkspaceSearch'),
        parameters=[param('q',S(minLength=2,maxLength=120),True),param('limit',I(minimum=1,maximum=20,default=10))],
        description='Trimmed q must contain >=2 characters. Assets match literal name/address prefixes. Event message/kind/device ID matches are case-insensitive contains within only the latest100 events in the last7days. Each group has at most limit results; an empty event group is not a full-history no-match assertion. History outages preserve available assets and set recentEventsStatus=UNAVAILABLE. Page-name results belong to the frontend route index.')

    list_params = [param('deviceId',S(maxLength=64,default='')),param('q',S(maxLength=120,default='')),
        param('cursor',S(maxLength=512,default='')),param('limit',I(minimum=1,maximum=100,default=50))]
    list_description = 'Organization-scoped creation-time DESC, ID DESC keyset page. q is a literal title prefix. Reset the cursor when filters change. Metadata pages omit large evidence/configuration content. Source=CONTROL; mode is explicit CONNECTED or process-local DEMO.'
    endpoint('/workbench/incidents','get','Incident queue without note bodies',page('IncidentSummary'),parameters=list_params+[param('status',S(enum=['','OPEN','INVESTIGATING','RESOLVED'],default=''))],description=list_description+' Incident list records contain noteCount instead of note bodies; fetch the incident detail to read notes.')
    endpoint('/workbench/incidents','post','Create an incident linked to an authorized asset',R('Incident'),body=R('IncidentInput'),status='201',
        description='New OPEN incident with revision1. Optional alert must belong to the same device; assignee must be an enabled account in the organization. Mutation and audit persist atomically.')
    endpoint('/workbench/incidents/{id}','get','Read an authorized incident',R('Incident'))
    endpoint('/workbench/incidents/{id}/updates','post','Update incident and append a note with optimistic concurrency',R('Incident'),body=R('IncidentUpdate'),
        description='Current revision is required; stale revisions return409. Resolving requires a nonblank note. Existing notes are retained, maximum100. Title/status/assignee update and audit commit atomically.')
    endpoint('/workbench/evidence','get','Evidence metadata directory',page('EvidenceMetadata'),parameters=list_params,description=list_description)
    endpoint('/workbench/evidence','post','ADMIN: import immutable text evidence and SHA-256 checksum',R('Evidence'),body=R('EvidenceInput'),status='201',roles=['ADMIN'],
        description='Text import only, max32768 Java characters and65536 UTF-8 bytes. Exact content UTF-8 bytes determine SHA-256. Integrity remains UNSIGNED. No signature, WORM, or automatic collection is implied.')
    endpoint('/workbench/evidence/{id}','get','Verify and read full evidence with an audit entry',R('Evidence'),description='A recomputed SHA-256 mismatch returns 409 EVIDENCE_INTEGRITY_FAILURE and records an integrity-failure audit; mismatched content is not returned as verified evidence.')
    endpoint('/workbench/evidence/{id}/manifest','get','Read checksum manifest with explicit UNSIGNED integrity',R('Manifest'),
        description='Audited export read; the server recomputes the checksum before returning content. A mismatch returns 409 EVIDENCE_INTEGRITY_FAILURE. SHA-256 is independently verifiable against evidence.content UTF-8 bytes. version1, algorithmSHA-256, integrityUNSIGNED, worm=false. No digital signature or WORM guarantee.')
    endpoint('/workbench/configuration/snapshots','get','Configuration snapshot metadata directory',page('SnapshotMetadata'),parameters=list_params,description=list_description)
    endpoint('/workbench/configuration/snapshots','post','ADMIN: import a redacted read-only configuration snapshot',R('Snapshot'),body=R('ConfigurationInput'),status='201',roles=['ADMIN'],
        description='At most65536 Java characters/131072 UTF-8 bytes/2000 lines. Common credential lines and private-key blocks are redacted before storage and hashing. Content is never executed or pushed to a device. Mutations and audits commit atomically.')
    endpoint('/workbench/configuration/snapshots/{id}','get','Verify and read a stored redacted snapshot with an audit',R('Snapshot'),description='Checksum mismatch returns 409 CONFIGURATION_INTEGRITY_FAILURE and records an integrity-failure audit.')
    endpoint('/workbench/configuration/diff','get','Bounded deterministic line diff between snapshots of one device',R('Diff'),
        parameters=[param('before',S(maxLength=64),True),param('after',S(maxLength=64),True)],
        description='Both snapshots must be owned by the current organization and belong to the same device. At most2000 lines per snapshot. CPU work runs on a bounded diff scheduler. Both checksums are recomputed before diffing; a mismatch returns 409 CONFIGURATION_INTEGRITY_FAILURE. Both reads are audited. No restore/deploy action.')
    endpoint('/workbench/checks','get','Native and externally reported probe definitions and their latest result',page('Check'),parameters=list_params,description=list_description)
    endpoint('/workbench/checks','post','Register a read-only probe definition',R('Check'),body=R('CheckInput'),status='201',
        description='Creates revision 1. MANUAL definitions have execution=NATIVE_WORKER: enabled definitions are scheduled by the collector worker with four concurrent checks per instance and a distributed per-definition lease. SYNTHETIC fixtures remain COLLECTOR_REPORTED and are never executed against real targets. lastResult remains null until an actual result is persisted. Saves do not issue an immediate synchronous probe.')
    endpoint('/workbench/checks/{id}','get','Read a probe definition and latest observation',R('Check'))
    endpoint('/workbench/checks/{id}/updates','post','Update a probe definition with current revision',R('Check'),body=R('CheckUpdate'),
        description='Device, type, and provenance remain fixed. Archived definitions cannot be updated. A stale revision returns 409. A committed definition update clears lastResult; previous results remain in history under their original definitionRevision.')
    endpoint('/workbench/checks/{id}/run','post','Run a saved MANUAL definition once and persist the actual result',R('CheckResult'),body=R('Revision'),roles=['ADMIN','OPERATOR'],
        description='Reads the organization-scoped definition and requires its current revision. Allowed while the schedule is disabled; does not enable scheduling. Archived, stale-revision and SYNTHETIC definitions reject with 409; an active distributed lease rejects with 409 CHECK_ALREADY_RUNNING; local concurrency exhaustion returns 429 CHECK_BUSY. At most four simultaneous checks per instance, ten-second overall probe budget and three-second socket connect/read budgets. All DNS results must pass the configured device-network TargetPolicy; sockets connect to the validated literal address, preventing DNS rebinding. HTTP GET reads only a bounded status line and never follows redirects or sends credentials. TLS/HTTPS use normal system trust plus target identity validation. DNS reports system-resolver resolution into allowed networks, not authoritative DNS-server health. Invalid or policy-denied targets persist UNKNOWN; connection, HTTP >=400, TLS and timeout failures persist FAIL. A successful operation is PASS with measured latency; HTTP 3xx records the status and that no redirect was followed. Result source is noeriva-native-<type>, provenance stays MANUAL, and concurrent definition changes reject obsolete results rather than updating current health.')
    endpoint('/workbench/checks/{id}/archive','post','Archive a definition without deleting its history',R('Check'),body=R('Revision'),
        description='Requires current revision; sets archived=true and enabled=false. Archived checks reject further updates/results. Existing observations remain readable.')
    endpoint('/workbench/checks/{id}/results','get','Bounded observation-time page of retained probe results',page('CheckResult'),
        parameters=[param('cursor',S(maxLength=512,default='')),param('limit',I(minimum=1,maximum=100,default=30))],
        description='Check ownership required. Observation time DESC, caller result ID DESC keyset ordering. Unknown/missing latency is null, never an invented zero.')
    endpoint('/workbench/check-results','post','COLLECTOR or ADMIN: idempotently report a probe observation',R('CheckResult'),body=R('CheckResultInput'),status='201',roles=['COLLECTOR','ADMIN'],
        description='Stable caller ID, matching check provenance required. PASS requires finite latency0..600000ms. Same ID/body replays return the retained result; conflicting payloads return409. Archived checks and mismatched definitionRevision reject reports with 409. Omitted/null definitionRevision means revision 1. Late results for the current definition remain in history without replacing a newer checkpoint; older definitions remain retained history, not current status.')
    endpoint('/workbench/network-evidence','post','COLLECTOR or ADMIN: import typed temporal NAT/lease evidence',R('NetworkEvidence'),body=R('NetworkInput'),status='201',roles=['COLLECTOR','ADMIN'],
        description='Stable caller ID; identical replay idempotent, conflicting replay409. NAT requires both literal IPs, ports1..65535 andTCP/UDP. ADDRESS_LEASE forbids all NAT-only fields. Half-open interval is positive and<=31days; validFrom>=2000 and<=now+120s; validTo<=now+31days. Uncertainty is0..300000ms. The site is copied from device inventory on import, never accepted from the caller. For NAT the device is the gateway; for a lease it is the subject. No DNS resolution or real-device discovery occurs.')
    endpoint('/workbench/investigations','post','Read-only temporal candidate correlation with audited query',R('Investigation'),body=R('InvestigationInput'),roles=['ADMIN','OPERATOR','VIEWER'],
        description='Maximum100 NAT and100 lease candidates; exceeding bounds fails429 instead of truncating a determination. CONFIRMED requires exactly one COMPLETE NAT and matching COMPLETE lease in the same captured site and provenance, covering the instant without clock uncertainty. NAT deviceId denotes the observing gateway; the lease deviceId denotes the subject, so they may differ. Overlaps/conflicting device claims/uncertainty are AMBIGUOUS. Missing lease or snapshot-only evidence is INSUFFICIENT_EVIDENCE. NO_MATCH means no retained matching NAT. Query ID is an audit identifier, not a stored evidence export. No personnel or packet-payload attribution.')
    endpoint('/workbench/audit','get','Organization-scoped audit trail including workbench reads and writes',page('Audit'),
        parameters=[param('resourceId',S(maxLength=128,default='')),param('from',T),param('to',T),param('cursor',S(maxLength=512,default='')),param('limit',I(minimum=1,maximum=100,default=50))],
        description='Positive range<=31days. Defaults to the preceding7days. Creation time DESC, ID DESC cursor page. Credentials and evidence content are not duplicated into audit rows.')
    for path, operations in helpers['paths'].items():
        if path.startswith('/workbench/'):
            for operation in operations.values():
                for parameter in operation.get('parameters', []):
                    if parameter['in'] == 'path':
                        parameter['schema'] = S(maxLength=64)
