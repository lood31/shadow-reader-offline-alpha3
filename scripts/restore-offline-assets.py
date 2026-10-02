"""Restore the exact verified resources from the delivered APK into a source checkout."""
import hashlib,json,shutil,sys,zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
destination=root/'app/src/offline/assets/pronunciation'
receipt=json.loads((root/'backend/models/offline/export-manifest.json').read_text(encoding='utf-8'))
assert receipt['validationStatus']=='PASS'
with zipfile.ZipFile(sys.argv[1]) as z:
    manifest=json.loads(z.read('assets/pronunciation/manifest.json'))
    assert manifest['validationReportSha256']==receipt['validationReportSha256']
    assert manifest['files']['model.int8.onnx']['sha256']==receipt['files']['model.int8.onnx']['sha256']
    for name,spec in manifest['files'].items():
        path=destination/name
        assert path.resolve().is_relative_to(destination.resolve()),name
        path.parent.mkdir(parents=True,exist_ok=True)
        temp=path.with_name(path.name+'.part')
        with z.open('assets/pronunciation/'+name) as source,temp.open('wb') as target:
            shutil.copyfileobj(source,target,65536)
        with temp.open('rb') as source: digest=hashlib.file_digest(source,'sha256').hexdigest()
        assert temp.stat().st_size==spec['bytes'] and digest==spec['sha256'],name
        temp.replace(path)
    (destination/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
print('Restored verified offline resources.')
