"""Stage the existing, verified Android alpha3 assets without downloading/re-exporting models."""
import argparse
import hashlib
import json
import shutil
from pathlib import Path

IOS = Path(__file__).resolve().parents[1]
REPO = IOS.parent

def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()

def verify(root, manifest):
    checked = 0
    for name, spec in manifest["files"].items():
        file = (root / name).resolve()
        if not file.is_relative_to(root.resolve()) or not file.is_file():
            raise ValueError(f"Missing or unsafe asset: {name}")
        if file.stat().st_size != spec["bytes"] or digest(file) != spec["sha256"]:
            raise ValueError(f"Corrupt asset: {name}")
        checked += 1
    return checked

def prepare(source=None):
    source = Path(source).resolve() if source else REPO / "app/src/offline/assets/pronunciation"
    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
    assert (manifest["onnxruntime"], manifest["g2pVersion"], manifest["evidenceVersion"]) == ("1.24.3", "espeak-ng-1.52.0", "acoustic-evidence-v1")
    receipt = json.loads((REPO / "backend/models/offline/export-manifest.json").read_text(encoding="utf-8"))
    assert receipt["validationStatus"] == "PASS", "Original export parity gate has not passed"
    assert manifest["validationReportSha256"] == receipt["validationReportSha256"]
    assert digest(REPO / "backend/runs/offline-parity/report.json") == receipt["validationReportSha256"]
    count = verify(source, manifest)
    target = IOS / "Resources/pronunciation"
    target.mkdir(parents=True, exist_ok=True)
    for name, spec in manifest["files"].items():
        out = (target / name).resolve()
        if not out.is_relative_to(target.resolve()):
            raise ValueError("Unsafe target path")
        out.parent.mkdir(parents=True, exist_ok=True)
        if out.is_file() and out.stat().st_size == spec["bytes"] and digest(out) == spec["sha256"]:
            continue
        temporary = out.with_name(out.name + ".part")
        shutil.copyfile(source / name, temporary)
        if digest(temporary) != spec["sha256"]:
            raise ValueError(f"Copy verification failed: {name}")
        temporary.replace(out)
    shutil.copyfile(source / "manifest.json", target / "manifest.json")
    fixtures = IOS / "Tests/Fixtures"
    fixtures.mkdir(parents=True, exist_ok=True)
    for name in ("evidence.json", "g2p.json"):
        shutil.copyfile(REPO / "app/src/test/resources/pronunciation" / name, fixtures / name)
    provenance = {
        "status": "STAGED_AND_HASH_VERIFIED", "assetCount": count,
        "assetBytes": sum(v["bytes"] for v in manifest["files"].values()),
        "manifestSha256": digest(target / "manifest.json"),
        "validationReportSha256": receipt["validationReportSha256"],
        "iosCompiled": False, "iosInferenceVerified": False,
    }
    (IOS / "Resources/resource-receipt.json").write_text(json.dumps(provenance, indent=2)+"\n", encoding="utf-8")
    print(json.dumps(provenance, ensure_ascii=False))

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", help="Existing alpha3 pronunciation asset directory")
    prepare(parser.parse_args().source)
