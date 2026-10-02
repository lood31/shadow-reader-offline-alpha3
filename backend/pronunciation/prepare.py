"""Explicit one-time download into the project, locking immutable model revision."""
import hashlib
import json
from pathlib import Path
from huggingface_hub import snapshot_download
from .engine import MODEL_ID, ROOT

def main():
    bundle=ROOT/'models'/'primary'
    revision='ae45363bf3413b374fecd9dc8bc1df0e24c3b7f4'
    snapshot_download(MODEL_ID,revision=revision,local_dir=bundle,
                      allow_patterns=['*.json','pytorch_model.bin','README.md'])
    files={}
    for name in ['pytorch_model.bin','config.json','preprocessor_config.json','tokenizer_config.json','vocab.json']:
        path=bundle/name
        with path.open('rb') as f:
            files[name]=hashlib.file_digest(f,'sha256').hexdigest()
    manifest={'modelId':MODEL_ID,'revision':revision,'license':'Apache-2.0','sha256':files}
    (bundle/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    print(json.dumps(manifest,indent=2))

if __name__=='__main__': main()
