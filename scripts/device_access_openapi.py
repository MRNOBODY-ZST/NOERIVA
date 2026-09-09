"""Public device-access DTOs and operations; credential storage internals are never schemas."""


def extend(schemas, endpoint, helpers):
    S, N, I, R, A, O = (helpers[key] for key in ['S', 'N', 'I', 'R', 'A', 'O'])
    T, NT, NS, B = (helpers[key] for key in ['T', 'NULL_T', 'NULL_S', 'B'])
    param = helpers['param']
    revision = I(format='int64', minimum=1)
    nullable = lambda name: {'anyOf': [R(name), {'type': 'null'}]}
    enum_text = lambda values, maximum: {'type': ['string', 'null'], 'enum': ['', *values, None], 'maxLength': maximum}
    auth = ['MD5', 'SHA1', 'SHA256', 'SHA512']
    privacy = ['AES128', 'DES']
    levels = ['noAuthNoPriv', 'authNoPriv', 'authPriv']
    protocols = S(enum=['SNMP', 'REDFISH', 'SSH'])
    status = S(enum=['NOT_TESTED', 'QUEUED', 'RUNNING', 'SUCCESS', 'PARTIAL', 'ERROR', 'DISABLED'])
    decimal = {**NS, 'pattern': '^[0-9]+$', 'description': 'Unsigned decimal string; preserve exact precision. Null means unavailable, not zero.'}
    secret = {**NS, 'maxLength': 1024, 'writeOnly': True, 'pattern': r'^(?![*•●]+$)[^\u0000]*$',
              'description': 'Null, empty or absent retains the saved secret only for the same normalized credential identity. Never returned. Mask-only placeholders and NUL are rejected.'}
    passphrase = {**secret, 'anyOf': [{'enum': ['', None]}, S(minLength=8, maxLength=1024)],
                  'description': secret['description'] + ' A newly supplied SNMPv3 authentication/privacy passphrase requires at least eight characters.'}
    schemas['DeviceManagement'] = O({'device': R('Device'), 'inventoryRevision': {**revision, 'description': 'Inventory table revision, independent of Device.revision telemetry checkpoint; use this for updates.'}})
    schemas['DeviceUpdate'] = O({'revision': revision, **schemas['CreateDevice']['properties']}, ['revision', *schemas['CreateDevice']['required']])
    schemas['DeviceConnectionRevision'] = O({'revision': revision})
    schemas['DeviceCollectionState'] = O({'revision': revision, 'enabled': {**B, 'default': False}}, ['revision'])
    schemas['DeviceSecrets'] = {**O({'community': secret, 'authPassword': passphrase, 'privacyPassword': passphrase, 'password': secret}, []), 'writeOnly': True}
    settings = {
        'host': S(minLength=1, maxLength=253, pattern=r'^(?!-)(?!.*\.\.)(?!.*-$)[a-zA-Z0-9:.\-]{1,253}$', description='Hostname/IP only; no URL, path, embedded credentials or IPv6 zone identifier. Each read resolves and validates all addresses against configured CIDRs.'),
        'port': I(minimum=1, maximum=65535), 'intervalSeconds': I(minimum=15, maximum=86400),
        'timeoutMillis': I(minimum=250, maximum=10000), 'maxInterfaces': I(minimum=1, maximum=256),
        'username': {**NS, 'maxLength':120, 'description':'Required and nonblank for SNMPv3, Redfish and SSH. SSH rejects control characters. Ignored/normalized empty for v2c.'},
        'snmpVersion': {**enum_text(['2c', '3'], 10), 'description':'SNMP requires 2c or 3; ignored and normalized empty for Redfish/SSH.'},
        'securityLevel': {**enum_text(levels, 20), 'description':'Required for SNMPv3. Inactive for SNMPv2c, Redfish and SSH.'},
        'authProtocol': {**enum_text(auth, 20), 'description':'Required for authNoPriv/authPriv. No automatic fallback/downgrade. MD5/SHA1 are explicit legacy choices.'},
        'privacyProtocol': {**enum_text(privacy, 20), 'description':'Required only for authPriv. DES is explicit legacy compatibility. AES256 is unsupported.'},
        'contextName': {**NS, 'maxLength':64}, 'tlsMode': {**enum_text(['SYSTEM', 'PINNED'], 10), 'description':'Required for Redfish; SYSTEM verifies system trust and hostname; PINNED verifies the exact leaf-certificate SHA-256. No skip-verification option.'},
        'sshProfile': {**NS, 'enum':['HUAWEI_IMANA','DELL_OS9','CISCO_IOS_XE',None], 'maxLength':32, 'description':'Required for SSH; selects a fixed read-only command profile, never arbitrary commands. Non-SSH saves normalize to null.'},
        'sshHostKeySha256': {**NS, 'maxLength':60, 'pattern':r'^SHA256:[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]$', 'description':'Required for SSH: canonical OpenSSH SHA256 fingerprint of exactly 32 decoded bytes, base64 without padding. Verified before password authentication; no automatic trust or downgrade. Non-SSH saves normalize to null.'},
        'certificateSha256': {**NS, 'maxLength':95, 'description':'Required for PINNED: 64 hexadecimal digits after removing colons; canonical response is lowercase without separators. Empty for SYSTEM or SNMP.'},
    }
    schemas['DeviceConnectionSave'] = O({'revision': I(format='int64', minimum=0, default=0), **settings,
        'enabled': {**B, 'default':False}, 'secrets': {**nullable('DeviceSecrets'), 'writeOnly':True}},
        ['host', 'port', 'intervalSeconds', 'timeoutMillis', 'maxInterfaces'])
    schemas['DeviceConnectionSave']['description'] = ('revision=0 creates; existing records require matching revision. Slot comes from the path. New/changed SNMPv2c identity requires community; v3 requires username and the secrets matching its security level; Redfish requires username/password; SSH requires username/password, sshProfile and sshHostKeySha256. Identity compares host, port, normalized username, version/security algorithms, context, TLS policy/pin and SSH profile/host-key pin. Inactive legacy protocol fields normalize to empty; inactive SSH fields normalize to null. Changing identity prevents secret reuse. Save resets current readings/checkpoints and replaces the source epoch; enabled controls initial QUEUED versus NOT_TESTED. The console saves disabled and requests an explicit test; the API itself does not require a prior test to enable.')
    schemas['DeviceIdentity'] = O({key: NS for key in ['vendor','family','profileId','model','serialNumber','firmware','sysObjectId','sysName','description']})
    schemas['DeviceSensor'] = O({'id': S(), 'label':S(), 'metric':S(), 'unit':S(), 'value':{'type':['number','null']}, 'health':S(enum=helpers['HEALTH']), 'sourceRef':S()})
    schemas['DevicePort'] = O({'key':S(), 'name':S(), 'macAddress':NS, 'speedBps':decimal,
        'adminStatus':S(), 'operStatus':S(), 'inOctets':decimal, 'outOctets':decimal, 'discontinuity':decimal,
        'counterBits':I(enum=[32,64]), 'sourceRef':S()})
    schemas['DeviceReading'] = O({'observedAt':T, 'identity':R('DeviceIdentity'), 'health':S(enum=helpers['HEALTH']),
        'metrics':{'type':'object','additionalProperties':N(),'description':'Only actual finite summary readings. Unsupported/missing values are omitted; detailed sensors may include other metric names.'},
        'sensors':A(R('DeviceSensor')), 'ports':{**A(R('DevicePort')),'maxItems':256},
        'capabilities':A(S()), 'qualityFlags':A(S()), 'facts':{'type':'object','additionalProperties':S(), 'description':'Safe driver-generated facts, not raw CLI. SSH neighborObservations is a bounded JSON string of allowlisted ARP/LLDP/CDP records; evidence is not proof of current reachability.'}})
    common = {'slot':S(enum=['snmp','redfish','ssh']), 'protocol':protocols, 'revision':revision, 'enabled':B, 'status':status,
        'lastAttemptAt':NT, 'lastSuccessAt':NT, 'nextPollAt':NT, 'errorCode':NS, 'errorMessage':NS, 'lastReading':nullable('DeviceReading')}
    schemas['DeviceCollectionView'] = {**O(common), 'description':'Safe view for all read roles. No target settings, username, secret values, ciphertext, vault references or lease token. enabled is scheduling intent, not proof of successful data collection.'}
    # Legacy inactive protocol settings are empty strings; the added inactive SSH fields remain null.
    response_settings = {name: {**value, 'type': 'string' if name in ['host','username','snmpVersion','securityLevel','authProtocol','privacyProtocol','contextName','tlsMode','certificateSha256'] else value['type']} for name,value in settings.items()}
    for value in response_settings.values():
        if 'enum' in value and value['type'] == 'string': value['enum'] = [item for item in value['enum'] if item is not None]
    schemas['DeviceConnectionView'] = O({**common, **response_settings, **{key:B for key in ['hasCommunity','hasAuthPassword','hasPrivacyPassword','hasPassword']}})
    schemas['DeviceConnectionList'] = O({'items':{**A(R('DeviceConnectionView')), 'maxItems':3}, 'asOf':T})
    schemas['DeviceCollectionList'] = O({'items':{**A(R('DeviceCollectionView')), 'maxItems':3}, 'asOf':T})
    schemas['DeviceSupportProfile'] = O({'id':S(), 'vendor':S(), 'family':S(), 'protocols':A(protocols), 'implemented':B,
        'verification':S(enum=['SIMULATOR_TESTED_HARDWARE_PENDING','HARDWARE_VERIFIED_SCOPED'], description='HARDWARE_VERIFIED_SCOPED applies only to the model, firmware and protocol stated in notes; it is not whole-family hardware certification. See docs/devices/MONITORING-INTEGRITY-20260909.md and HARDWARE-VERIFICATION.md.'), 'notes':S()})
    schemas['DeviceSupport'] = O({'items':A(R('DeviceSupportProfile')), 'protocols':A(protocols), 'credentialStorageReady':B, 'collectorEnabled':B})
    schemas['DeviceSupport']['description'] = 'protocols lists installed drivers. credentialStorageReady requires a configured vault key and connected storage. collectorEnabled is the NOERIVA_DEVICE_POLLING_AVAILABLE deployment hint, not a gate for saving or manually testing a connection.'
    endpoint('/device-support','get','Installed protocol drivers and vendor support evidence',R('DeviceSupport'))
    endpoint('/devices/{id}/management','get','Inventory metadata and independent edit revision',R('DeviceManagement'))
    endpoint('/devices/{id}/updates','post','Edit inventory with an independent optimistic revision',R('DeviceManagement'),body=R('DeviceUpdate'),description='ADMIN/OPERATOR. Existing organization-local site required. Metadata/audit commit together. Management-address edits do not redirect any saved connection. 409 for revision conflict; demo cannot persist and returns 503.')
    endpoint('/devices/{id}/connections','get','ADMIN: independent SNMP, Redfish and SSH connection settings',R('DeviceConnectionList'),roles=['ADMIN'],description='Replaces the old topology alias. Use GET /topology?deviceId=... for relationship graphs. Usernames and secret-presence flags are ADMIN-only; secrets/ciphertext never returned. Empty in the demo profile.')
    slot = [param('slot',S(enum=['snmp','redfish','ssh']),True,'path')]
    endpoint('/devices/{id}/connections/{slot}','post','ADMIN: save target and write-only credentials',R('DeviceConnectionView'),parameters=slot,body=R('DeviceConnectionSave'),roles=['ADMIN'],description='200 for both create and update. 409 for stale revision, duplicate create or a currently leased connection; retry after re-reading. Requires connected storage and a configured AES-GCM vault key. Never logs or returns plaintext/ciphertext. Device source mapping is snmp->network, redfish->bmc and ssh->ssh.')
    endpoint('/devices/{id}/connections/{slot}/state','post','ADMIN: change periodic collection intent',R('DeviceConnectionView'),parameters=slot,body=R('DeviceCollectionState'),roles=['ADMIN'],description='Increments revision and enters QUEUED or DISABLED. Disabling releases the disabled connection metric bindings. No prior successful test is required by the API. During an active read lease, state change returns 409; it does not cancel an already-issued device request. History remains retained.')
    for action in ['test','collect']:
        endpoint('/devices/{id}/connections/{slot}/'+action,'post',f'ADMIN: synchronous read-only device {action}',R('DeviceConnectionView'),parameters=slot,body=R('DeviceConnectionRevision'),roles=['ADMIN'],description=('Uses saved credentials and checks revision before acquiring a bounded read lease. A stale/busy connection returns 409 DEVICE_BUSY_OR_CHANGED. At most 16 protocol reads per process; protocol work is bounded to 30 seconds, publication to 24 seconds, with a 65-second admission budget. Device/protocol/publication failures are recorded and returned as HTTP 200 ConnectionView with status ERROR, errorCode/errorMessage and timestamps. A successful read is SUCCESS or PARTIAL according to qualityFlags; missing values remain absent. ' + ('Testing remains available for disabled connections, records identity/readings only and does not publish samples or enable scheduling.' if action=='test' else 'Collection requires an enabled connection; otherwise 409 DEVICE_COLLECTION_DISABLED before leasing or network work. Collection publishes safe observations/metrics through existing stores. Publication is not atomic across providers; PUBLICATION_FAILED may leave some samples retained.')))
    endpoint('/devices/{id}/collection','get','Safe device collection snapshots for all read roles',R('DeviceCollectionList'),description='Includes at most one result for each of snmp/network, redfish/bmc and ssh/ssh; at most three results in total. Failed attempts do not clear the last successful reading. Demo returns an empty list for an existing synthetic device.')
    endpoint('/devices/{id}/live','get','Authenticated collection snapshot SSE',description='Event collection carries JSON DeviceCollectionList immediately and every 3 seconds. At most 64 streams per process, with a maximum lifetime of 10 minutes; cancellation releases capacity. Slow-client ticks may be dropped. No resume cursor or durable event ID. Send Authorization in headers, not URL, and fetch a fresh snapshot on reconnect. Response sets Cache-Control: no-store and X-Accel-Buffering: no.')
    op=helpers['paths']['/devices/{id}/live']['get']
    op['responses']['200']['content']={'text/event-stream':{'schema':S(description='SSE frames: event:collection followed by data:<DeviceCollectionList JSON>.')}}
    op['x-sse-event']='collection'
    op['x-sse-data-schema']=R('DeviceCollectionList')
    op['responses']['200']['headers']['X-Accel-Buffering']={'schema':S(enum=['no'])}
