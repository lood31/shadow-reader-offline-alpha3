"""Copy only reviewed iOS source/CI files into the isolated existing repository clone."""
import shutil
from pathlib import Path

IOS = Path(__file__).resolve().parents[1]
REPO = IOS.parent
DESTINATION = IOS / ".build/publish"
assert (DESTINATION / ".git").is_dir(), "Clone the intended GitHub repository first"
assert DESTINATION.resolve().is_relative_to((IOS / ".build").resolve())
excluded = {".build", ".qa", "Pods", "__pycache__", "xcuserdata"}
count = 0
for source in IOS.rglob("*"):
    relative = source.relative_to(IOS)
    if any(part in excluded for part in relative.parts):
        continue
    if relative.parts[:2] in [("Resources", "pronunciation"), ("Native", "Vendor")]:
        continue
    if not source.is_file():
        continue
    assert not source.is_symlink(), "Source symlink rejected"
    target = DESTINATION / "ios" / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
    count += 1
workflow = DESTINATION / ".github/workflows/ios-macos.yml"
workflow.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(REPO / ".github/workflows/ios-macos.yml", workflow)
native_inputs = [
    "third_party/espeak-ng-1.52.0/src/ucd-tools/CMakeLists.txt",
    "third_party/espeak-ng-1.52.0/src/libespeak-ng/CMakeLists.txt",
    "third_party/espeak-ng-1.52.0/src/libespeak-ng/config.h.in",
]
for name in native_inputs:
    source = REPO / name
    assert source.is_file(), f"Missing native build input: {name}"
    target = DESTINATION / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
readme = DESTINATION / "README.md"
text = readme.read_text(encoding="utf-8")
marker = "## iPhone / iOS experimental client"
if marker not in text:
    text += "\n"+marker+"\n\nIndependent SwiftUI iOS 17+ source is in [ios/](ios/README.md). Cloud macOS compilation and simulator tests are defined in [GitHub Actions](.github/workflows/ios-macos.yml); model delivery uses a SHA-256 verified Release bundle. Windows static checks passed; Xcode, simulator and iPhone results are pending until real runs complete. This is a self-use experimental client, with no signed IPA or App Store distribution.\n"
    readme.write_text(text, encoding="utf-8")
print(f"Staged {count} iOS files, workflow, {len(native_inputs)} native build inputs and README; Android files untouched")
