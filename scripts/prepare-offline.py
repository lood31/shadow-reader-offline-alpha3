"""Prepare verified, bundled pronunciation assets using backend's pinned environment."""
import hashlib
import json
import shutil
from pathlib import Path
import espeakng_loader
import silero_vad

ROOT = Path(__file__).resolve().parents[1]

def sha(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source,'sha256').hexdigest()

def main():
    model = ROOT/'backend/models/offline'
    receipt = json.loads((model/'export-manifest.json').read_text())
    report = ROOT/'backend/runs/offline-parity/report.json'
    assert receipt['validationStatus'] == 'PASS', 'Model acoustic parity gate has not passed'
    assert sha(report) == receipt['validationReportSha256'], 'Parity receipt mismatch'
    assert sha(model/'model.int8.onnx') == receipt['files']['model.int8.onnx']['sha256']
    destination = ROOT/'app/src/offline/assets/pronunciation'
    destination.mkdir(parents=True,exist_ok=True)
    shutil.copy2(model/'model.int8.onnx',destination/'model.int8.onnx')
    shutil.copy2(ROOT/'backend/models/primary/vocab.json',destination/'vocab.json')
    vad = Path(silero_vad.__file__).parent/'data/silero_vad.onnx'
    shutil.copy2(vad,destination/'vad.onnx')
    shutil.copytree(espeakng_loader.get_data_path(),destination/'espeak-ng-data',dirs_exist_ok=True)
    license_dir = destination/'licenses'; license_dir.mkdir(exist_ok=True)
    shutil.copy2(ROOT/'third_party/espeak-ng-1.52.0/COPYING',license_dir/'espeak-ng-GPL-3.txt')
    shutil.copy2(ROOT/'third_party/espeak-ng-1.52.0/src/ucd-tools/COPYING',license_dir/'ucd-tools-license.txt')
    shutil.copy2(ROOT/'third_party/espeak-ng-1.52.0/src/ucd-tools/COPYING.UCD',license_dir/'unicode-license.txt')
    shutil.copytree(ROOT/'third_party/offline-notices',license_dir,dirs_exist_ok=True)
    shutil.copy2(Path(silero_vad.__file__).parent.parent/'silero_vad-6.2.0.dist-info/licenses/LICENSE',license_dir/'silero-vad-MIT.txt')
    files = {p.relative_to(destination).as_posix():{'bytes':p.stat().st_size,'sha256':sha(p)}
             for p in sorted(destination.rglob('*')) if p.is_file() and p.name != 'manifest.json'}
    manifest = {'bundleVersion':'offline-int8-v1','modelVersion':receipt['revision'],
                'modelId':receipt['modelId'],'onnxruntime':'1.24.3','g2pVersion':'espeak-ng-1.52.0',
                'vadVersion':'silero-vad-6.2.0','evidenceVersion':'acoustic-evidence-v1',
                'validationReportSha256':receipt['validationReportSha256'],
                'sourceArchiveSha256':sha(ROOT/'.tools/espeak-ng-1.52.0.zip'),
                'files':files}
    (destination/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'files':len(files),'bytes':sum(f['bytes'] for f in files.values()),'bundle':str(destination)},indent=2))

if __name__ == '__main__':main()
