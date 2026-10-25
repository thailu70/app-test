"""Check the owner-approved Play candidate before local upload-key signing."""
import hashlib
import json
import struct
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile

bundle, manifest_path, config_path = map(Path, sys.argv[1:4])
android = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(manifest_path).getroot()
assert manifest.get('package') == 'live.passo.app', 'Unexpected permanent package ID'
assert manifest.get(android + 'versionName') == '1.1.23', 'Unexpected release version'
assert int(manifest.get(android + 'versionCode')) == 26, 'Unexpected release version code'
assert int(manifest.find('uses-sdk').get(android + 'targetSdkVersion')) >= 36
application = manifest.find('application')
assert application.get(android + 'debuggable', 'false') == 'false', 'A Play candidate must not be debuggable'
assert application.get(android + 'usesCleartextTraffic') == 'false', 'Cleartext traffic is forbidden'
permissions = sorted(node.get(android + 'name') for node in manifest.findall('uses-permission'))
for forbidden in ['ACCESS_BACKGROUND_LOCATION', 'READ_SMS', 'SEND_SMS', 'MANAGE_EXTERNAL_STORAGE', 'QUERY_ALL_PACKAGES']:
    assert 'android.permission.' + forbidden not in permissions, 'Unexpected sensitive permission: ' + forbidden
def is_location_service(node):
    value = node.get(android + 'foregroundServiceType', '')
    # bundletool can print compiled Android flags as integer values rather than
    # their source XML names. ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION = 8.
    try:
        location_type = int(value, 0) == 8
    except ValueError:
        location_type = value == 'location'
    return node.get(android + 'name', '').endswith('.DriverLocationService') and location_type

assert any(is_location_service(node)
           for node in application.findall('service')), 'Driver location must retain its declared foreground-service type'
assert 'PAGE_ALIGNMENT_16K' in config_path.read_text(encoding='utf-8'), 'Bundle configuration must preserve 16 KB native-library page alignment'

native = []
with ZipFile(bundle) as archive:
    assert 'base/dex/classes.dex' in archive.namelist(), 'Missing compiled app'
    assert not any(name.upper().startswith('META-INF/') and name.upper().endswith(('.RSA', '.DSA', '.EC')) for name in archive.namelist()), 'CI artifact should be unsigned; keep the upload key on the owner computer'
    for name in archive.namelist():
        if not name.endswith('.so') or not any('/lib/' + abi + '/' in name for abi in ['arm64-v8a', 'x86_64']):
            continue
        data = archive.read(name)
        assert data[:6] == b'\x7fELF\x02\x01', 'Expected a little-endian 64-bit ELF library: ' + name
        header_offset = struct.unpack_from('<Q', data, 32)[0]
        header_size, count = struct.unpack_from('<HH', data, 54)
        loads = 0
        for index in range(count):
            offset = header_offset + index * header_size
            if struct.unpack_from('<I', data, offset)[0] != 1:
                continue
            file_offset, address = struct.unpack_from('<QQ', data, offset + 8)
            alignment = struct.unpack_from('<Q', data, offset + 48)[0]
            assert alignment >= 16384 and (address - file_offset) % 16384 == 0, 'Library requires a 16 KB-compatible rebuild: ' + name
            loads += 1
        assert loads, 'ELF library has no loadable segments: ' + name
        native.append(name)
assert native, 'Expected the bundled barcode scanner native libraries'
print(json.dumps({'package': manifest.get('package'), 'versionName': manifest.get(android + 'versionName'),
                  'versionCode': 26, 'targetSdk': int(manifest.find('uses-sdk').get(android + 'targetSdkVersion')),
                  'debuggable': False, 'unsigned': True, 'permissions': permissions, 'native16KbChecked': native,
                  'sha256': hashlib.sha256(bundle.read_bytes()).hexdigest()}, indent=2))
