"""Bounded review of saved enabled SNMP-first neighbor facts; no network scanning or physical merge."""


def extend(schemas, endpoint, helpers):
    S, N, I, R, A, O = (helpers[key] for key in ['S', 'N', 'I', 'R', 'A', 'O'])
    T, NT, NS = (helpers[key] for key in ['T', 'NULL_T', 'NULL_S'])
    param = helpers['param']
    statuses = ['NEW', 'EXISTING', 'LINKED', 'REGISTERED', 'POSSIBLE_DUPLICATE', 'CONFLICT']
    revision = I(format='int64', minimum=1)
    ident = S(minLength=1, maxLength=64, pattern=r'\S')
    cidr = S(maxLength=32, pattern=r'^(?:0|[1-9][0-9]{0,2})(?:\.(?:0|[1-9][0-9]{0,2})){3}/(?:2[4-9]|3[0-2])$',
             description='Canonical literal IPv4 network /24–/32. Each octet must be <=255 and host bits zero. No wildcard, DNS, leading zero, 0/8, loopback, link-local or multicast/reserved range. Filters saved evidence; does not scan the range.')
    schemas['DiscoveryRunInput'] = O({'sourceDeviceIds': {**A(ident), 'minItems':1, 'maxItems':8, 'uniqueItems':True}, 'cidr':cidr, 'siteId':ident})
    schemas['DiscoveryRegisterInput'] = O({'revision':revision, 'name':S(minLength=1,maxLength=120,pattern=r'\S'), 'type':S(enum=['HOST','BMC','SWITCH','ROUTER','FIREWALL'])})
    schemas['DiscoveryLinkInput'] = O({'revision':revision, 'deviceId':ident})
    schemas['DiscoverySourceResult'] = O({'deviceId':ident,'deviceName':S(), 'status':S(enum=['USED','MISSING','STALE','INVALID']),
        'observedAt':NT, 'acceptedCount':I(minimum=0,maximum=256), 'reason':{**NS,'enum':[None,'NO_SAVED_NEIGHBORS','SOURCE_NOT_FRESH','INVALID_NEIGHBOR_JSON']}})
    schemas['DiscoveryRun'] = O({'id':S(format='uuid'), 'siteId':ident, 'cidr':cidr, 'asOf':T,
        'sourcesRequested':I(minimum=1,maximum=8), 'sourcesUsed':I(minimum=0,maximum=8),
        **{key:I(minimum=0,maximum=2048) for key in ['observationsRead','candidatesUpdated','existingCount','duplicateCount','conflictCount']},
        'sources':{**A(R('DiscoverySourceResult')),'minItems':1,'maxItems':8}, 'qualityFlags':A(S())})
    schemas['DiscoveryEvidence'] = O({'sourceDeviceId':ident,'sourceDeviceName':S(), 'source':S(enum=['ARP','LLDP','CDP','DHCP']),
        'observedAt':T,'address':S(format='ipv4'), 'mac':{**NS,'pattern':r'^(?:[0-9a-f]{2}:){5}[0-9a-f]{2}$'},
        **{key:{**NS,'maxLength':size} for key,size in [('interfaceName',120),('vlan',64),('name',120),('chassisId',256),('chassisSubtype',32),('portId',256)]},
        'ttlSeconds':{'type':['integer','null'],'format':'int64','minimum':0,'maximum':65535},
        'ageMinutes':{'type':['number','null'],'minimum':0,'description':'Age reported by the source ARP table, not time since candidate storage. Null means unknown.'},
        'validUntil':{**NT,'description':'Observed time plus known LLDP/CDP TTL. Null means validity was not proven.'}, 'qualityFlags':A(S())})
    schemas['DiscoveryCandidate'] = O({'id':S(format='uuid'),'revision':revision,'address':S(format='ipv4'),'siteId':ident,
        'name':{**NS,'maxLength':120},'mac':{**NS,'pattern':r'^(?:[0-9a-f]{2}:){5}[0-9a-f]{2}$'}, 'status':S(enum=statuses),
        'reasons':A(S()), 'evidence':{**A(R('DiscoveryEvidence')),'maxItems':16}, 'associatedDeviceId':{**NS,'maxLength':64},
        'firstSeenAt':T,'lastSeenAt':{**T,'description':'Latest accepted source observation, not run time or proof the target is online.'}})
    endpoint('/discovery/runs','post','Review saved neighbor evidence within an explicit IPv4 CIDR',R('DiscoveryRun'),body=R('DiscoveryRunInput'),
        description='ADMIN/OPERATOR. Synchronous transaction; no scan, protocol call, credential read or automatic collect. Source devices must belong to the authorized organization and requested site. For each device selects exactly one enabled source, preferring SNMP over SSH, before inspecting saved LLDP/CDP, SNMP IP-MIB/RFC1213 ARP and DHCP Snooping facts. DHCP bindings are device-local evidence, not a complete DHCP server lease list. Invalid/incomplete/local neighbor records and expired known DHCP leases are excluded. Missing, invalid or stale preferred SNMP never falls back to saved SSH. Reads must be less than 15 minutes old and not in the future. At most 256 observations/source, 2048/run; expired known TTL records are excluded. Invalid/stale/missing sources are reported independently. Upserts candidates by organization/site/address, never registers assets automatically. Shared MAC/name/key is not physical identity. Admission max 4 per process; related review window max 1024 rows; excess returns 429 DISCOVERY_CAPACITY. Overall 10-second budget including transaction, 503 DISCOVERY_TIMEOUT on timeout; unavailable storage returns 503 DISCOVERY_UNAVAILABLE.')
    candidate_page=O({'items':{**A(R('DiscoveryCandidate')),'maxItems':100},'nextCursor':{**NS,'maxLength':36},'asOf':T,'source':S(enum=['DISCOVERY']),'mode':S(enum=['CONNECTED'])})
    endpoint('/discovery/candidates','get','Authorized candidate metadata and bounded supporting evidence',candidate_page,
        parameters=[param('siteId',S(maxLength=64,default='')),param('status',S(enum=['',*statuses],maxLength=32,default='')),
                    param('cursor',S(maxLength=36,default='')),param('limit',I(minimum=1,maximum=100,default=50))],
        description='All read roles. Keyset page ordered by candidate ID; use the returned cursor unchanged, resetting after filter changes. Keeps older candidates with original observation times. At most 16 evidence records per candidate. No raw CLI or credentials are returned. Empty results or missing sources do not establish that a network has no devices.')
    endpoint('/discovery/candidates/{id}/register','post','Explicitly register a reviewed candidate as an UNKNOWN asset',R('DiscoveryCandidate'),body=R('DiscoveryRegisterInput'),
        description='ADMIN/OPERATOR. Locks the candidate and site, rechecks inventory, and commits association, new inventory and audit together. New registration requires matching revision (409 REVISION_CONFLICT). An already associated candidate returns idempotently. CONFLICT cannot register (409 CANDIDATE_CONFLICT); POSSIBLE_DUPLICATE requires explicit operator choice and retains reasons. Copies no credentials, enables no collection, and merges no existing assets/history. Admission/transaction bounds match runs.')
    endpoint('/discovery/candidates/{id}/link','post','Explicitly associate a candidate with a same-site existing asset',R('DiscoveryCandidate'),body=R('DiscoveryLinkInput'),
        description='ADMIN/OPERATOR. Target must be in the same authorized organization and site. Matching revision required for a new link; re-linking the same target is idempotent, a different existing association returns 409 CANDIDATE_ALREADY_ASSOCIATED. An explicit CONFLICT link is allowed with reasons retained. Association does not change the asset address/settings, copy secrets, merge physical assets or migrate history. Admission/transaction bounds match runs.')
    for path in ['/discovery/candidates/{id}/register','/discovery/candidates/{id}/link']:
        for parameter in helpers['paths'][path]['post']['parameters']:
            if parameter['name']=='id': parameter['schema']=S(maxLength=36)
