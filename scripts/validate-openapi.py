#!/usr/bin/env python3
"""Check that the committed contract covers every API mapping and workspace/workbench DTO field."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'services/noeriva-control/src/main/java/io/noeriva/control'
contract = json.loads((ROOT / 'schemas/api/openapi.json').read_text())
errors = []
actual = set()
for source in JAVA.rglob('*.java'):
    text = source.read_text()
    prefix = re.search(r'@RequestMapping\("(/api/v1[^"\n]*)"\)', text)
    if not prefix or '@RestController' not in text:
        continue
    for mapping in re.finditer(r'@(Get|Post|Put|Patch|Delete)Mapping(?:\(([^)]*)\))?', text):
        arguments = mapping.group(2) or ''
        routes = re.findall(r'"(/[^"\n]*)"', arguments) or ['']
        for route in routes:
            actual.add((mapping.group(1).lower(), (prefix.group(1) + route).removeprefix('/api/v1')))
documented = {(method, path) for path, operations in contract['paths'].items()
              for method in operations if method in {'get', 'post', 'put', 'patch', 'delete'}}
for key in sorted(actual - documented):
    errors.append(f'Missing operation: {key[0].upper()} {key[1]}')
for key in sorted(documented - actual):
    errors.append(f'Undeclared controller operation: {key[0].upper()} {key[1]}')


def record_fields(text, public_only=True):
    pattern = r'public record (\w+)\(' if public_only else r'\b(?:public\s+)?record (\w+)\('
    for match in re.finditer(pattern, text):
        start = match.end()
        depth, quoted, escape = 1, False, False
        for index in range(start, len(text)):
            char = text[index]
            if quoted:
                if escape:
                    escape = False
                elif char == '\\':
                    escape = True
                elif char == '"':
                    quoted = False
            elif char == '"':
                quoted = True
            elif char == '(':
                depth += 1
            elif char == ')':
                depth -= 1
                if depth == 0:
                    body = text[start:index]
                    break
        else:
            raise ValueError(f'Unclosed record {match.group(1)}')
        clean = re.sub(r'@\w+(?:\([^)]*\))?\s*', '', body)
        # A field separator is followed by a Java type, while generic map commas are not.
        parts = re.split(r',\s*(?![^<]*>)', clean)
        fields = {part.strip().rsplit(' ', 1)[1]: part.strip().rsplit(' ', 1)[0] for part in parts}
        yield match.group(1), fields

# New controllers use typed DTOs, so ensure each declared request and response uses that DTO.
for filename in ['WorkspaceController.java', 'WorkbenchController.java']:
    text = (JAVA / filename).read_text()
    prefix = re.search(r'@RequestMapping\("(/api/v1[^"\n]*)"\)', text).group(1).removeprefix('/api/v1')
    mappings = list(re.finditer(r'@(Get|Post)Mapping\("([^"\n]*)"\)', text))
    for index, mapping in enumerate(mappings):
        block = text[mapping.end():mappings[index + 1].start() if index + 1 < len(mappings) else len(text)]
        signature = re.search(r'public Mono<(.+?)>\s+\w+\((.*?)\)\s*\{', block, re.S)
        if not signature:
            errors.append(f'Cannot inspect signature for {filename}: {mapping.group(2)}')
            continue
        op = contract['paths'].get(prefix + mapping.group(2), {}).get(mapping.group(1).lower())
        if not op:
            continue
        status = '201' if '@ResponseStatus(HttpStatus.CREATED)' in block else '200'
        response = op.get('responses', {}).get(status, {}).get('content', {}).get('application/json', {}).get('schema', {})
        result = signature.group(1)
        if result.startswith('Page<'):
            expected = result[5:-1]
            declared = response.get('properties', {}).get('items', {}).get('items', {}).get('$ref')
        else:
            expected = result
            declared = response.get('$ref')
        if declared != '#/components/schemas/' + expected:
            errors.append(f'{mapping.group(1).upper()} {prefix + mapping.group(2)}: response does not match {result}')
        body = re.search(r'@RequestBody\s+(\w+)\s+\w+', signature.group(2))
        if body:
            declared = op.get('requestBody', {}).get('content', {}).get('application/json', {}).get('schema', {}).get('$ref')
            if declared != '#/components/schemas/' + body.group(1):
                errors.append(f'{mapping.group(1).upper()} {prefix + mapping.group(2)}: request does not match {body.group(1)}')

checked_records = 0
device_aliases = {
    'Management':'DeviceManagement', 'Update':'DeviceUpdate', 'Revision':'DeviceConnectionRevision',
    'State':'DeviceCollectionState', 'Save':'DeviceConnectionSave', 'SecretInput':'DeviceSecrets',
    'ConnectionView':'DeviceConnectionView', 'CollectionView':'DeviceCollectionView',
    'Identity':'DeviceIdentity', 'Sensor':'DeviceSensor', 'Port':'DevicePort', 'Reading':'DeviceReading',
}
discovery_aliases = {'RunInput':'DiscoveryRunInput','RegisterInput':'DiscoveryRegisterInput','LinkInput':'DiscoveryLinkInput',
    'RunResult':'DiscoveryRun','SourceResult':'DiscoverySourceResult','Candidate':'DiscoveryCandidate','Evidence':'DiscoveryEvidence'}
for filename in ['WorkspaceModels.java', 'WorkbenchModels.java', 'AlertSummaryController.java', 'devices/DeviceAccessModels.java', 'devices/DeviceProtocol.java', 'discovery/DiscoveryModels.java']:
    for name, fields in record_fields((JAVA / filename).read_text(), public_only=not filename.endswith('DeviceProtocol.java')):
        # These records are private persistence/driver contracts, never API responses.
        if filename.startswith('devices/') and name in {'Settings','Stored','Target','Secrets'}:
            continue
        schema_name = device_aliases[name] if filename.startswith('devices/') else discovery_aliases[name] if filename.startswith('discovery/') else name
        checked_records += 1
        schema = contract['components']['schemas'].get(schema_name)
        if schema is None:
            errors.append(f'Missing DTO schema: {name}')
            continue
        documented_fields = set(schema.get('properties', {}))
        if documented_fields != set(fields):
            errors.append(f'{name} field mismatch: missing={set(fields)-documented_fields}, extra={documented_fields-set(fields)}')
        for field, kind in fields.items():
            value = schema.get('properties', {}).get(field, {})
            types = value.get('type', [])
            types = [types] if isinstance(types, str) else types
            primitive = {'int': 'integer', 'long': 'integer', 'Long': 'integer', 'Integer': 'integer', 'Double': 'number', 'boolean': 'boolean', 'String': 'string', 'Instant': 'string'}.get(kind)
            if primitive and primitive not in types:
                errors.append(f'{name}.{field}: {kind} requires {primitive}, got {value}')
            if kind.startswith('List<') and 'array' not in types:
                errors.append(f'{name}.{field}: expected array')
            if kind.startswith('Map<') and 'object' not in types:
                errors.append(f'{name}.{field}: expected object')

# DeviceAccessController lives in a subpackage and also returns Items<T>, Map and SSE.
# Inspect every real signature, preserving the original workspace/workbench checks above.
device_text = (JAVA / 'devices/DeviceAccessController.java').read_text()
device_mappings = list(re.finditer(r'@(Get|Post)Mapping\((?:value=)?"([^"\n]*)"[^)]*\)', device_text))
for index, mapping in enumerate(device_mappings):
    method, path = mapping.group(1).lower(), mapping.group(2)
    op = contract['paths'].get(path, {}).get(method)
    if not op:
        continue  # Already reported by full controller coverage.
    block = device_text[mapping.end():device_mappings[index+1].start() if index+1<len(device_mappings) else len(device_text)]
    declared = op.get('responses', {}).get('200', {}).get('content', {}).get('application/json', {}).get('schema', {})
    if path == '/device-support':
        if declared.get('$ref') != '#/components/schemas/DeviceSupport':
            errors.append('Device support map requires its typed public response schema')
    elif path.endswith('/live'):
        if 'text/event-stream' not in op['responses']['200'].get('content', {}) or op.get('x-sse-data-schema') != {'$ref':'#/components/schemas/DeviceCollectionList'}:
            errors.append('Device live SSE must document safe DeviceCollectionList data')
    else:
        signature = re.search(r'public Mono<(.+?)>\s+\w+\((.*?)\)\s*\{', block, re.S)
        if not signature:
            errors.append(f'Cannot inspect DeviceAccessController signature: {path}')
            continue
        result = signature.group(1)
        expected = {'Models.Items<ConnectionView>':'DeviceConnectionList', 'Models.Items<CollectionView>':'DeviceCollectionList'}.get(result, device_aliases.get(result))
        if declared.get('$ref') != '#/components/schemas/' + str(expected):
            errors.append(f'{method.upper()} {path}: response does not match {result}')
        body = re.search(r'@RequestBody\s+(\w+)\s+\w+', signature.group(2))
        if body:
            body_ref = op.get('requestBody', {}).get('content', {}).get('application/json', {}).get('schema', {}).get('$ref')
            if body_ref != '#/components/schemas/' + device_aliases[body.group(1)]:
                errors.append(f'{method.upper()} {path}: body does not match {body.group(1)}')
    expected_roles = ['ADMIN'] if '/connections' in path else ['ADMIN','OPERATOR'] if method == 'post' else ['ADMIN','OPERATOR','VIEWER']
    if op.get('x-required-roles') != expected_roles:
        errors.append(f'{method.upper()} {path}: wrong device-access role boundary')
    if '{slot}' in path and not any(p['name']=='slot' and p.get('schema', {}).get('enum')==['snmp','redfish','ssh'] for p in op.get('parameters', [])):
        errors.append(f'{path}: missing exact device slot enum')

for key in ['community','authPassword','privacyPassword','password']:
    if not contract['components']['schemas'].get('DeviceSecrets', {}).get('properties', {}).get(key, {}).get('writeOnly'):
        errors.append(f'DeviceSecrets.{key} must remain writeOnly')
for name in ['DeviceConnectionView','DeviceCollectionView']:
    props = contract['components']['schemas'].get(name, {}).get('properties', {})
    if set(props).intersection({'secrets','ciphertext','community','authPassword','privacyPassword','password','lease','leaseToken'}):
        errors.append(f'{name}: contains a secret or internal lease field')
    if name == 'DeviceCollectionView' and set(props).intersection({'host','port','username','certificateSha256','sshProfile','sshHostKeySha256'}):
        errors.append('DeviceCollectionView must not expose ADMIN connection settings')


# Discovery aliases are explicit because Evidence already names a workbench DTO.
discovery_text = (JAVA / 'discovery/DiscoveryController.java').read_text()
discovery_mappings = list(re.finditer(r'@(Get|Post)Mapping\("([^"\n]*)"\)', discovery_text))
for index, mapping in enumerate(discovery_mappings):
    method, path = mapping.group(1).lower(), '/discovery' + mapping.group(2)
    op = contract['paths'].get(path, {}).get(method)
    if not op:
        continue
    block = discovery_text[mapping.end():discovery_mappings[index+1].start() if index+1<len(discovery_mappings) else len(discovery_text)]
    signature = re.search(r'public Mono<(.+?)>\s+\w+\((.*?)\)\s*\{', block, re.S)
    if not signature:
        errors.append(f'Cannot inspect DiscoveryController signature: {path}')
        continue
    response = op.get('responses', {}).get('200', {}).get('content', {}).get('application/json', {}).get('schema', {})
    result = signature.group(1)
    if result.startswith('Page<'):
        expected = discovery_aliases[result[5:-1]]
        declared = response.get('properties', {}).get('items', {}).get('items', {}).get('$ref')
    else:
        expected = discovery_aliases[result]
        declared = response.get('$ref')
    if declared != '#/components/schemas/' + expected:
        errors.append(f'{method.upper()} {path}: response does not match {result}')
    body = re.search(r'@RequestBody\s+(\w+)\s+\w+', signature.group(2))
    if body:
        declared = op.get('requestBody', {}).get('content', {}).get('application/json', {}).get('schema', {}).get('$ref')
        if declared != '#/components/schemas/' + discovery_aliases[body.group(1)]:
            errors.append(f'{method.upper()} {path}: request does not match {body.group(1)}')
    if op.get('x-required-roles') != (['ADMIN','OPERATOR'] if method=='post' else ['ADMIN','OPERATOR','VIEWER']):
        errors.append(f'{method.upper()} {path}: wrong discovery role boundary')
for name in ['DeviceConnectionList','DeviceCollectionList']:
    if contract['components']['schemas'][name]['properties']['items'].get('maxItems') != 3:
        errors.append(f'{name} must support three protocol slots')
if contract['components']['schemas']['DeviceReading']['properties']['ports'].get('maxItems') != 256:
    errors.append('DeviceReading must preserve the bounded 256-port limit independently of slot count')
for name in ['DeviceConnectionSave','DeviceConnectionView']:
    for field in ['sshProfile','sshHostKeySha256']:
        if contract['components']['schemas'][name]['properties'].get(field, {}).get('type') != ['string','null']:
            errors.append(f'{name}.{field} must retain null for non-SSH connections')


def references(value):
    if isinstance(value, dict):
        if '$ref' in value:
            yield value['$ref']
        for item in value.values():
            yield from references(item)
    elif isinstance(value, list):
        for item in value:
            yield from references(item)

for ref in references(contract):
    if not ref.startswith('#/components/schemas/') or ref.rsplit('/', 1)[-1] not in contract['components']['schemas']:
        errors.append(f'Unresolved schema reference {ref}')
for path, operations in contract['paths'].items():
    for method, op in operations.items():
        if method not in {'get', 'post', 'put', 'patch', 'delete'}:
            continue
        required_paths = set(re.findall(r'\{([^}]+)\}', path))
        declared_paths = {p['name'] for p in op.get('parameters', []) if p['in'] == 'path' and p.get('required')}
        if required_paths != declared_paths:
            errors.append(f'{method.upper()} {path}: path parameter mismatch')
        if method == 'post' and not any(p['name'] == 'X-Noeriva-Request' and p.get('required') for p in op.get('parameters', [])):
            errors.append(f'{method.upper()} {path}: missing request protection header')
if errors:
    raise SystemExit('\n'.join(errors))
print(f'Validated {len(actual)} controller operations, {len(contract["paths"])} paths, {checked_records} exact DTO field sets and all schema references.')
