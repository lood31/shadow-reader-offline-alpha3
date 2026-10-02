"""Verify the delivered offline assets and all native 16 KiB ELF/ZIP alignment."""
import hashlib,json,struct,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
APK=ROOT/'artifacts/ShadowReader-2.0.0-alpha3-offline-debug.apk'
def sha(data): return hashlib.sha256(data).hexdigest()
with zipfile.ZipFile(APK) as z, APK.open('rb') as raw:
    manifest=json.loads(z.read('assets/pronunciation/manifest.json'))
    for name,spec in manifest['files'].items():
        data=z.read('assets/pronunciation/'+name)
        assert len(data)==spec['bytes'] and sha(data)==spec['sha256'],name
    libs=[]
    for item in z.infolist():
        if not item.filename.startswith('lib/') or not item.filename.endswith('.so'): continue
        assert item.filename.startswith('lib/arm64-v8a/'),item.filename
        data=z.read(item);assert data[:5]==b'\x7fELF\x02'
        offset=struct.unpack_from('<Q',data,32)[0];size,count=struct.unpack_from('<HH',data,54)
        alignments=[]
        for i in range(count):
            header=struct.unpack_from('<IIQQQQQQ',data,offset+i*size)
            if header[0]==1:
                assert header[7]>=16384,item.filename
                assert header[2]%16384==header[3]%16384,item.filename
                alignments.append(header[7])
        raw.seek(item.header_offset+26);n,e=struct.unpack('<HH',raw.read(4))
        payload=item.header_offset+30+n+e
        assert item.compress_type==zipfile.ZIP_STORED and payload%16384==0,item.filename
        libs.append({'file':item.filename,'elfLoadAlignments':alignments,'zipOffset':payload})
    assert any('shadow_phonemes' in x['file'] for x in libs)
    assert any('onnxruntime' in x['file'] for x in libs)
report={'status':'PASS','apkBytes':APK.stat().st_size,'apkSha256':hashlib.file_digest(APK.open('rb'),'sha256').hexdigest(),
        'verifiedAssets':len(manifest['files']),'nativeLibraries':libs,'deviceAcceptance':'NOT_TESTED'}
(ROOT/'artifacts/alpha3-package-validation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report,indent=2))
