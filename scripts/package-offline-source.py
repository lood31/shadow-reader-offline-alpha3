"""Private source delivery, excluding keys, recordings outside regression fixtures, caches and weights."""
from pathlib import Path
import hashlib,zipfile
ROOT=Path(__file__).resolve().parents[1]
output=ROOT/'artifacts/ShadowReader-2.0.0-alpha3-source.zip'
skip={'build','.cxx','.gradle','.kotlin','.venv','__pycache__','.pytest_cache','.git','models','runs','data'}
files=[]
for folder in ['app','backend','docs','scripts','gradle','third_party']:
    for p in (ROOT/folder).rglob('*'):
        if p.is_file() and not (set(p.relative_to(ROOT/folder).parts)&skip):
            if p.name == 'offline-alpha3-stage1.md' or p.name.startswith('pronunciation-v2-'):continue
            if p.is_relative_to(ROOT/'app/src/offline/assets'):continue
            if p.suffix.lower() in {'.keystore','.jks','.log'}:continue
            files.append(p)
files += [ROOT/name for name in ['README.md','.gitignore','build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew','gradlew.bat']]
files += [ROOT/name for name in ['backend/models/offline/export-manifest.json','backend/runs/offline-parity/report.json',
    'backend/runs/offline-parity/g2p-api-parity.json',
    'app/src/offline/assets/pronunciation/manifest.json','.tools/espeak-ng-1.52.0.zip']]
with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for p in sorted(set(files)):z.write(p,p.relative_to(ROOT).as_posix())
    z.write(ROOT/'third_party/espeak-ng-1.52.0/COPYING','COPYING-alpha3-GPL-3.txt')
    z.writestr('SOURCE-DISTRIBUTION.txt',
        'Shadow Reader alpha3 private experimental distribution, GPL-3.0-or-later.\n'
        'Third-party sources and resources retain their respective licenses and copyrights.\n'
        'Complete application/native source and build scripts are supplied here.\n'
        'Restore verified model resources from the companion APK with scripts/restore-offline-assets.py.\n'
        'Tests and benchmark use synthetic fixtures; no user recordings are included.\n'
        'Debug signing key is excluded; use original workspace key to preserve upgrade signature.\n')
with output.open('rb') as source:digest=hashlib.file_digest(source,'sha256').hexdigest()
output.with_suffix('.zip.sha256').write_text(f'{digest}  {output.name}\n',encoding='ascii')
print(f'{len(files)} files, {output.stat().st_size} bytes: {output}')
