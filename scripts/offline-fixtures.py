"""Reproducible public-corpus fixture builder for independent Kotlin/native port checks."""
import json
import ctypes
import os
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
import sys
sys.path.insert(0,str(ROOT/'backend'))
from pronunciation.offline_export import log_softmax
from pronunciation.evidence import assess_phones
from pronunciation.engine import text_words
import espeakng_loader

def main():
    run = ROOT/'backend/runs/offline-parity'
    cases = json.loads((run/'cases.json').read_text(encoding='utf-8'))
    vocab = json.loads((ROOT/'backend/models/primary/vocab.json').read_text(encoding='utf-8'))
    ids = [v for k,v in vocab.items() if not k.startswith('<') and k not in ('|',' ')]
    # Use the same C API and flags as JNI; verify cleaning against phonemizer gold.
    os.environ['ESPEAK_DATA_PATH'] = espeakng_loader.get_data_path()
    dll = ctypes.CDLL(espeakng_loader.get_library_path())
    dll.espeak_Initialize.argtypes = [ctypes.c_int,ctypes.c_int,ctypes.c_char_p,ctypes.c_int]
    dll.espeak_SetVoiceByName.argtypes = [ctypes.c_char_p]
    dll.espeak_TextToPhonemes.argtypes = [ctypes.POINTER(ctypes.c_void_p),ctypes.c_int,ctypes.c_int]
    dll.espeak_TextToPhonemes.restype = ctypes.c_char_p
    dll.espeak_Initialize(1,0,None,0); assert dll.espeak_SetVoiceByName(b'en-us') == 0
    gold = {}
    for spec in cases:
        original = ROOT/'backend/runs/speechocean-pilot'/f'{spec["id"]}.json'
        assessment = json.loads(original.read_text(encoding='utf-8'))
        for word in assessment['words']:
            key = word['text'].lower().replace('’',"'").replace('‘',"'")
            expected = [p['ipa'] for p in word['phonemes']]
            if key in gold: assert gold[key] == expected
            gold[key] = expected
    mismatches = []
    for word,expected in gold.items():
        text = ctypes.create_string_buffer(word.encode()); pointer = ctypes.c_void_p(ctypes.addressof(text)); chunks=[]
        while pointer.value:
            value = dll.espeak_TextToPhonemes(ctypes.byref(pointer),1,2|(ord('_')<<8))
            if value:chunks.append(value.decode().replace('ˈ','').replace('ˌ',''))
        actual = [p for chunk in chunks for section in chunk.split('_') for p in section.split()]
        if actual != expected:mismatches.append({'word':word,'expected':expected,'actual':actual})
    report = {'uniqueWords':len(gold),'mismatches':mismatches,
              'scope':'same-version desktop C API/JNI flag parity; Android native runtime still requires device check'}
    (run/'g2p-api-parity.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    (ROOT/'app/src/test/resources/pronunciation/g2p.json').write_text(json.dumps(gold,ensure_ascii=False),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False))
    assert not mismatches
    import subprocess
    subprocess.run([sys.executable,str(ROOT/'scripts/synthetic-evidence-fixture.py')],check=True)

if __name__ == '__main__':main()
