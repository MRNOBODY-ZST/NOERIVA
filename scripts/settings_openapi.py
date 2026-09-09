"""Operational settings, rebuildable entity search and read-only capture."""
def extend(schemas, endpoint, h):
    S,I,R,A,O,T,B=[h[k] for k in ['S','I','R','A','O','T','B']]
    NS,NT=h['NULL_S'],h['NULL_T'];param=h['param']
    values={'revision':I(minimum=0),'organizationName':S(minLength=1,maxLength=120),'timezone':S(),
      'defaultCollectionIntervalSeconds':I(minimum=15,maximum=86400),'defaultTimeoutMillis':I(minimum=250,maximum=10000),
      'defaultMaxInterfaces':I(minimum=1,maximum=256),'configurationSyncEnabled':B,'configurationSyncIntervalSeconds':I(minimum=300,maximum=86400)}
    schemas['SettingsInput']=O(values)
    schemas['Settings']=O({**values,'updatedAt':NT,'updatedBy':NS,'account':O({'username':S(),'roles':A(S()),'organizationId':S()}),
      'runtime':O({'mode':h['MODE'],'searchProvider':S(),'searchStatus':S(),'searchLastIndexedAt':NT,'configurationCapture':S(enum=['READ_ONLY']),'historyRetention':S(enum=['NO_AUTOMATIC_DELETION'])})})
    schemas['SearchHit']=O({k:S() for k in ['kind','id','deviceId','name','deviceName','address','source']}|{'indexedAt':T})
    schemas['EntitySearch']=O({'items':A(R('SearchHit')),'provider':S(enum=['ELASTICSEARCH']),'status':S(enum=['READY']),'indexedAt':NT})
    schemas['CaptureState']=O({'status':S(enum=['NOT_CAPTURED','RUNNING','SUCCESS','UNCHANGED','ERROR','UNSUPPORTED']),'message':S(),'capturedAt':NT,
      'snapshot':{'anyOf':[R('Snapshot'),{'type':'null'}]},'source':NS,'lastAttemptAt':NT})
    endpoint('/settings','get','Organization defaults, current account and live search status',R('Settings'))
    endpoint('/settings','post','ADMIN: save organization defaults with optimistic revision',R('Settings'),body=R('SettingsInput'),roles=['ADMIN'],description='Defaults initialize new connections. Existing connection settings are preserved. configurationSyncEnabled controls the periodic read-only worker. No history is deleted.')
    endpoint('/settings/password','post','Change own password and revoke previous sessions',O({'changed':B,'reauthenticate':B}),
      body=O({'currentPassword':S(writeOnly=True,maxLength=1024),'newPassword':S(writeOnly=True,minLength=12,maxLength=72,description='Maximum 72 UTF-8 bytes.')}),roles=['ADMIN','OPERATOR','VIEWER'],description='Verifies current password; password update uses compare-and-set. Existing bearer handles are revoked. Re-authentication with the new password is required.')
    endpoint('/search','get','Organization-scoped Elasticsearch device and interface directory',R('EntitySearch'),[param('q',S(minLength=2,maxLength=120),True),param('limit',I(minimum=1,maximum=60,default=20))],description='Literal case-insensitive contains search. Index contains entity identifiers/names/addresses only, never connection credentials or raw configuration. Provider timeout/partial shards return 503, not partial substitute results.')
    endpoint('/search/reindex','post','ADMIN: start bounded background entity index refresh',O({'provider':S(),'status':S(),'lastIndexedAt':NT,'reindexing':B}),roles=['ADMIN'])
    for method in ['get','post']:
      endpoint('/workbench/configuration/devices/{id}/capture',method,'Read synchronization status' if method=='get' else 'ADMIN: read selected enabled device configuration source',R('CaptureState'),roles=['ADMIN','OPERATOR','VIEWER'] if method=='get' else ['ADMIN'],description='Selects one enabled connection in order SNMP, Redfish, SSH. Failure never starts an implicit fallback SSH session. SNMP_DEVICE_BASELINE and other protocol baselines contain only identity/interface baseline, not a complete running configuration. Only a selected enabled Dell OS9/Cisco IOS XE SSH connection reads show running-config through a separate pinned SSH session. Secret patterns are redacted before persistence. Unchanged content does not create duplicates. Failure remains ERROR/UNSUPPORTED; prior success timestamp is preserved. No device configuration write occurs.')
    props=schemas['WorkspaceSearch']['properties']
    props.update({'interfaces':A(R('WorkspaceInterface')),'provider':S(),'status':S(),'indexedAt':NT,'eventScanLimit':I(minimum=0,maximum=100),'eventWindowFrom':NT,'eventWindowTo':NT,'eventScope':S(enum=['ENTITY_DIRECTORY','RECENT_7_DAYS_MAX_100']),'recentEventsStatus':S(enum=['AVAILABLE','UNAVAILABLE','NOT_INDEXED'])})
    for name in ['interfaces','provider','status','indexedAt']:
      if name not in schemas['WorkspaceSearch']['required']:schemas['WorkspaceSearch']['required'].append(name)
    for name in ['Snapshot','SnapshotMetadata']:
        schemas[name]['properties']['provenance']=S(enum=['MANUAL','SYNTHETIC','DEVICE_READ_ONLY'])
    h['paths']['/workspace/search']['get']['summary']='Elasticsearch device/interface search with authorized current-state hydration'
    h['paths']['/workspace/search']['get']['description']='Connected mode uses Elasticsearch; interface matches include owning device IDs for direct navigation. Demo retains synthetic search. Event history is queried on its separate typed history endpoint.'
