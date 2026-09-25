"""Read-only ZIP/ELF metadata audit. Does not load native code or decompile it."""
import hashlib
import io
import json
import struct
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SDK = ROOT / 'third_party/live2d/sdk-r5/CubismSdkForJava-5-r.5'
aar = SDK / 'Core/android/Live2DCubismCore.aar'
result = {'aar_sha256': hashlib.sha256(aar.read_bytes()).hexdigest()}
with zipfile.ZipFile(aar) as archive:
    result['manifest'] = archive.read('AndroidManifest.xml').decode()
    result['aar_metadata'] = archive.read('META-INF/com/android/build/gradle/aar-metadata.properties').decode()
    result['elf'] = []
    for name in archive.namelist():
        if not name.endswith('.so'):
            continue
        data = archive.read(name)
        assert data[:4] == b'\x7fELF' and data[5] == 1
        bits = 64 if data[4] == 2 else 32
        machine = struct.unpack_from('<H', data, 18)[0]
        phoff = struct.unpack_from('<Q' if bits == 64 else '<I', data, 32 if bits == 64 else 28)[0]
        entsize, count = struct.unpack_from('<HH', data, 54 if bits == 64 else 42)
        loads = []
        relro = []
        for i in range(count):
            fields = struct.unpack_from('<IIQQQQQQ' if bits == 64 else '<IIIIIIII', data, phoff + i * entsize)
            if fields[0] == 0x6474e552:
                addr, size = (fields[3], fields[6]) if bits == 64 else (fields[2], fields[5])
                relro.append({'vaddr': addr, 'memsz': size, 'end_aligned_16k': (addr + size) % 16384 == 0})
            if fields[0] == 1:
                offset, vaddr, alignment = (fields[2], fields[3], fields[7]) if bits == 64 else (fields[1], fields[2], fields[7])
                loads.append({'offset': offset, 'vaddr': vaddr, 'alignment': alignment,
                              'aligned_16k': alignment >= 16384 and (vaddr - offset) % 16384 == 0})
        result['elf'].append({'path': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
                              'bits': bits, 'machine': machine, 'loads': loads, 'relro': relro})
    with zipfile.ZipFile(io.BytesIO(archive.read('classes.jar'))) as jar:
        result['classes'] = [{'name': n, 'major': struct.unpack_from('>H', jar.read(n), 6)[0]}
                             for n in jar.namelist() if n.endswith('.class')]

def compare(left, right):
    def files(base):
        return {p.relative_to(base).as_posix(): p for p in base.rglob('*') if p.is_file() and '.git' not in p.parts}
    a, b = files(left), files(right)
    changed = [n for n in a.keys() & b.keys() if a[n].read_bytes().replace(b'\r\n', b'\n') != b[n].read_bytes().replace(b'\r\n', b'\n')]
    return {'left_count': len(a), 'right_count': len(b), 'left_only': sorted(a.keys()-b.keys()),
            'right_only': sorted(b.keys()-a.keys()), 'changed_normalized_lf': sorted(changed)}

result['framework_source_comparison'] = compare(SDK/'Framework/framework/src', ROOT/'.upstream/cubism-framework-audit/framework/src')
result['sample_full_comparison'] = compare(SDK/'Sample/src/full', ROOT/'.upstream/cubism-java-audit/Sample/src/full')
result['sample_minimum_comparison'] = compare(SDK/'Sample/src/minimum', ROOT/'.upstream/cubism-java-audit/Sample/src/minimum')
print(json.dumps(result, indent=2))
