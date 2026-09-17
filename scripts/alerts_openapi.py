"""Alert-state summary used consistently by navigation and the scoped queue."""
def extend(schemas, endpoint, helpers):
    O, I, T, R, S, param = (helpers[name] for name in ('O', 'I', 'T', 'R', 'S', 'param'))
    schemas['AlertSummary'] = O({name: I(minimum=0, format='int64') for name in ('open', 'acknowledged', 'resolved', 'active')} | {'asOf': T})
    endpoint('/alerts/summary', 'get', 'Exact alert counts by state', R('AlertSummary'),
             parameters=[param('deviceId', S(default='', maxLength=64))],
             description='Organization-scoped, exact counts independent of queue pagination. Optional device scope returns 404 when unavailable in the current organization. active equals open plus acknowledged. Acknowledgement does not establish recovery. Navigation badges count open only; resolved and acknowledged history is retained.')
