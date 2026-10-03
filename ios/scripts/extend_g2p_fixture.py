"""Generate three supplemental gold words with the same eSpeak NG C API flags as iOS.

Run with backend/.venv/Scripts/python.exe on this Windows workspace. Existing 263-word
gold data is never changed. The generated supplement is delivered with the iOS sources.
"""
import ctypes
import json
from pathlib import Path
import espeakng_loader

IOS = Path(__file__).resolve().parents[1]
library = ctypes.CDLL(espeakng_loader.get_library_path())
library.espeak_Info.argtypes = [ctypes.c_void_p]
library.espeak_Info.restype = ctypes.c_char_p
version = library.espeak_Info(None).decode("utf-8")
assert version.startswith("1.52"), f"Unexpected eSpeak version: {version}"
library.espeak_Initialize.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_char_p, ctypes.c_int]
library.espeak_Initialize.restype = ctypes.c_int
library.espeak_SetVoiceByName.argtypes = [ctypes.c_char_p]
library.espeak_SetVoiceByName.restype = ctypes.c_int
library.espeak_TextToPhonemes.argtypes = [ctypes.POINTER(ctypes.c_void_p), ctypes.c_int, ctypes.c_int]
library.espeak_TextToPhonemes.restype = ctypes.c_char_p
root = IOS / "Resources/pronunciation"
assert library.espeak_Initialize(2, 0, str(root).encode(), 0) > 0
assert library.espeak_SetVoiceByName(b"en-us") == 0
gold = json.loads((IOS / "Tests/Fixtures/g2p.json").read_text(encoding="utf-8"))
extra = {}
for word in ("reader", "shadowing", "pronunciation"):
    assert word not in gold
    text = ctypes.create_string_buffer(word.encode())
    position = ctypes.c_void_p(ctypes.addressof(text))
    chunks = []
    while position.value:
        chunk = library.espeak_TextToPhonemes(ctypes.byref(position), 1, 2 | (ord("_") << 8))
        if chunk:
            chunks.append(chunk.decode("utf-8"))
    tokens = "_".join(chunks).replace("ˈ", "").replace("ˌ", "").replace("_", " ").split()
    assert tokens
    extra[word] = tokens
(IOS / "Tests/Fixtures/g2p-supplement.json").write_text(json.dumps(extra, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
(IOS / "Tests/Fixtures/g2p-provenance.json").write_text(json.dumps({"originalWords": len(gold), "supplementWords": len(extra), "totalWords": len(gold)+len(extra), "generator": "eSpeak NG desktop C API", "version": version, "voice": "en-us", "phonemeMode": "IPA with underscore separator; stress removed", "appleRuntimeVerified": False}, indent=2)+"\n", encoding="utf-8")
print(json.dumps(extra, ensure_ascii=True))
