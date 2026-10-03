"""Package/restore the exact existing assets for GitHub Actions; never export new weights."""
import argparse
import json
import shutil
import stat
import zipfile
from pathlib import Path
from prepare_resources import digest, verify

IOS = Path(__file__).resolve().parents[1]
CONTRACT = IOS / "Resources/ci-resource-bundle.json"


def package(output):
    root = IOS / "Resources/pronunciation"
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    count = verify(root, manifest)
    receipt = json.loads((IOS / "Resources/resource-receipt.json").read_text())
    assert digest(root / "manifest.json") == receipt["manifestSha256"]
    output = Path(output).resolve()
    assert output.is_relative_to((IOS / ".build").resolve()), "Archive must stay inside ios/.build"
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
        for name in sorted(set(manifest["files"]) | {"manifest.json"}):
            archive.write(root / name, name)
    contract = dict(releaseTag="ios-resources-v1", assetName=output.name, bytes=output.stat().st_size,
                    sha256=digest(output), manifestSha256=receipt["manifestSha256"], assetCount=count)
    CONTRACT.write_text(json.dumps(contract, indent=2)+"\n", encoding="utf-8")
    print(json.dumps(contract))


def restore(archive_path):
    contract = json.loads(CONTRACT.read_text())
    receipt = json.loads((IOS / "Resources/resource-receipt.json").read_text())
    archive_path = Path(archive_path)
    assert archive_path.stat().st_size == contract["bytes"], "Archive size mismatch"
    assert digest(archive_path) == contract["sha256"], "Archive SHA256 mismatch"
    root = IOS / "Resources/pronunciation"
    with zipfile.ZipFile(archive_path) as archive:
        manifest_bytes = archive.read("manifest.json")
        import hashlib
        assert hashlib.sha256(manifest_bytes).hexdigest() == contract["manifestSha256"] == receipt["manifestSha256"]
        manifest = json.loads(manifest_bytes)
        assert manifest["validationReportSha256"] == receipt["validationReportSha256"]
        expected = set(manifest["files"]) | {"manifest.json"}
        assert len(archive.namelist()) == len(expected) and set(archive.namelist()) == expected
        for name in sorted(expected):
            destination = (root / name).resolve()
            assert destination.is_relative_to(root.resolve()), "Unsafe archive path"
            info = archive.getinfo(name)
            assert not stat.S_ISLNK(info.external_attr >> 16), "Archive symlink rejected"
            wanted = len(manifest_bytes) if name == "manifest.json" else manifest["files"][name]["bytes"]
            assert info.file_size == wanted, "Asset length mismatch"
            destination.parent.mkdir(parents=True, exist_ok=True)
            temporary = destination.with_name(destination.name+".part")
            with archive.open(name) as source, temporary.open("wb") as target:
                shutil.copyfileobj(source, target, 1024*1024)
            if name != "manifest.json":
                assert digest(temporary) == manifest["files"][name]["sha256"], "Asset SHA256 mismatch"
            temporary.replace(destination)
    assert verify(root, manifest) == contract["assetCount"] == receipt["assetCount"]
    print("Restored exact verified iOS resources")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["package", "restore"])
    parser.add_argument("archive")
    args = parser.parse_args()
    if args.mode == "package":
        package(args.archive)
    else:
        restore(args.archive)
