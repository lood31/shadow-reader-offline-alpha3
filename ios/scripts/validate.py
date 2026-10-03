"""Static verification on Windows. This never claims Swift type-checking or iOS runtime success."""
import argparse
import ast
import json
import plistlib
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from prepare_resources import digest, verify

IOS = Path(__file__).resolve().parents[1]

def validate(resources_only=False, syntax=False):
    checks = []
    assets = IOS / "Resources/pronunciation"
    manifest = json.loads((assets / "manifest.json").read_text(encoding="utf-8"))
    count = verify(assets, manifest)
    receipt = json.loads((IOS / "Resources/resource-receipt.json").read_text(encoding="utf-8"))
    assert receipt["manifestSha256"] == digest(assets / "manifest.json")
    assert receipt["validationReportSha256"] == manifest["validationReportSha256"]
    assert count == receipt["assetCount"]
    checks.append(f"{count} staged model/VAD/G2P/license files: size and SHA256")
    assert manifest["onnxruntime"] == "1.24.3"
    assert manifest["g2pVersion"] == "espeak-ng-1.52.0"
    assert manifest["evidenceVersion"] == "acoustic-evidence-v1"
    for name in (
        "third_party/whisper.cpp-1.9.4/CMakeLists.txt",
        "third_party/whisper.cpp-1.9.4/include/whisper.h",
        "third_party/espeak-ng-1.52.0/src/ucd-tools/CMakeLists.txt",
        "third_party/espeak-ng-1.52.0/src/libespeak-ng/CMakeLists.txt",
        "third_party/espeak-ng-1.52.0/src/libespeak-ng/config.h.in",
        "third_party/espeak-ng-1.52.0/src/include/espeak-ng/speak_lib.h",
    ):
        assert (IOS.parent / name).is_file(), f"Missing native source/build input: {name}"
    checks.append("Native dependency CMake files, configuration template and public headers exist")
    if not resources_only:
        for path in (IOS / "scripts").glob("*.py"):
            ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        checks.append("Python resource/project/build verification scripts parse")
        graph = json.loads((IOS / "Config/project-graph.json").read_text())
        objects = graph["objects"]
        assert graph["rootObject"] in objects
        for identifier, obj in objects.items():
            assert re.fullmatch(r"[0-9A-F]{24}", identifier)
            for key in ("fileRef", "productRef", "package", "buildConfigurationList", "productReference", "target", "targetProxy", "containerPortal", "remoteGlobalIDString", "mainGroup", "productRefGroup"):
                if key in obj:
                    assert obj[key] in objects, f"Unresolved object {identifier}/{key}"
            for key in ("children", "files", "buildPhases", "dependencies", "buildConfigurations", "targets", "packageReferences", "packageProductDependencies"):
                assert all(item in objects for item in obj.get(key, [])), f"Unresolved objects {identifier}/{key}"
            if obj["isa"] == "PBXFileReference" and obj.get("sourceTree") == "SOURCE_ROOT":
                if obj["path"] == "Native/Vendor/ShadowNativeDependencies.xcframework":
                    continue # Build artifact produced on macOS, never replaced by a fake library.
                assert (IOS / obj["path"]).exists(), f"Missing source/resource {obj['path']}"
        targets = [obj for obj in objects.values() if obj["isa"] == "PBXNativeTarget"]
        assert {obj["name"] for obj in targets} == {"ShadowReader", "ShadowReaderShare", "ShadowReaderTests"}
        for target in targets:
            config = objects[target["buildConfigurationList"]]
            for key in config["buildConfigurations"]:
                assert objects[key]["buildSettings"]["IPHONEOS_DEPLOYMENT_TARGET"] == "17.0"
        source_refs = {obj["path"] for obj in objects.values() if obj["isa"] == "PBXFileReference" and obj.get("path", "").endswith(".swift")}
        actual_sources = {p.relative_to(IOS).as_posix() for folder in ("ShadowReader", "Shared", "ShareExtension", "Tests") for p in (IOS / folder).rglob("*.swift")}
        assert source_refs == actual_sources, "Swift source missing from project"
        checks.append("Xcode graph, targets, iOS 17 deployment and all Swift source references")
        pbx = (IOS / "ShadowReader.xcodeproj/project.pbxproj").read_text(encoding="utf-8")
        assert pbx.startswith("// !$*UTF8*$!")
        try:
            from openstep_parser import OpenStepDecoder
            decoded = OpenStepDecoder.ParseFromString(pbx)
            assert decoded["rootObject"] == graph["rootObject"]
            assert set(decoded["objects"]) == set(objects)
            checks.append("Generated project parses as OpenStep plist")
        except ImportError:
            checks.append("OpenStep parser unavailable; structural graph checked")
        app = plistlib.loads((IOS / "Config/App-Info.plist").read_bytes())
        share = plistlib.loads((IOS / "Config/Share-Info.plist").read_bytes())
        assert app["NSMicrophoneUsageDescription"]
        assert "NSAppTransportSecurity" not in app
        assert share["NSExtension"]["NSExtensionPointIdentifier"] == "com.apple.share-services"
        assert app["ShadowAppGroup"] == share["ShadowAppGroup"]
        entitlements = [plistlib.loads((IOS / f"Config/{name}.entitlements").read_bytes()) for name in ("App", "Share")]
        assert entitlements[0] == entitlements[1]
        assert entitlements[0]["com.apple.security.application-groups"] == ["$(SHADOW_APP_GROUP)"]
        ET.parse(IOS / "ShadowReader.xcodeproj/xcshareddata/xcschemes/ShadowReader.xcscheme")
        checks.append("Info plists, microphone disclosure, matching App Groups, shared test scheme")
        packages = [obj for obj in objects.values() if obj["isa"] == "XCRemoteSwiftPackageReference"]
        assert {obj["requirement"]["version"] for obj in packages} == {"2.6.0", "0.9.19"}
        assert "pod 'onnxruntime-c', '1.24.3'" in (IOS / "Podfile").read_text()
        native_script = (IOS / "scripts/build_native.sh").read_text()
        assert "simulator-arm64" in native_script and "simulator-x86_64" in native_script and "device-arm64" in native_script
        assert "rm -rf" not in native_script
        native_cmake = (IOS / "Native/Dependencies/CMakeLists.txt").read_text()
        assert "whisper.cpp-1.9.4" in native_cmake and "espeak-ng-1.52.0" in native_cmake
        checks.append("Pinned dependency versions and native device/simulator build inputs")
        for name in ("evidence.json", "g2p.json"):
            source = IOS.parent / "app/src/test/resources/pronunciation" / name
            assert digest(IOS / "Tests/Fixtures" / name) == digest(source)
        golden = json.loads((IOS / "Tests/Fixtures/g2p.json").read_text(encoding="utf-8"))
        supplement = json.loads((IOS / "Tests/Fixtures/g2p-supplement.json").read_text(encoding="utf-8"))
        assert len(golden) == 263 and len(supplement) == 3 and not (set(golden) & set(supplement))
        assert len(golden | supplement) == 266
        checks.append("Unmodified acoustic/263-word G2P fixtures plus 3 same-version C API supplemental words")
        tests = sum(len(re.findall(r"func test\w+\(", p.read_text(encoding="utf-8"))) for p in (IOS / "Tests").glob("*.swift"))
        if syntax:
            from tree_sitter import Language, Parser
            import tree_sitter_swift
            parser = Parser(Language(tree_sitter_swift.language()))
            for relative in sorted(actual_sources):
                data = (IOS / relative).read_bytes()
                tree = parser.parse(data)
                if tree.root_node.has_error:
                    errors = []
                    def walk(node):
                        if node.type == "ERROR" or node.is_missing:
                            errors.append(f"{relative}:{node.start_point.row+1}:{node.start_point.column+1} {data[node.start_byte:node.end_byte][:100]!r}")
                        for child in node.children:
                            walk(child)
                    walk(tree.root_node)
                    raise ValueError(f"Swift grammar errors in {relative}:\n"+"\n".join(errors))
            checks.append(f"Swift grammar parses ({len(actual_sources)} files); this is not type-checking")
        report = {
            "status": "PASS_STATIC_ONLY", "checks": checks, "xctestCasesDelivered": tests,
            "xctestExecuted": False, "xcodeBuild": "NOT_RUN_NO_MAC", "simulator": "NOT_RUN_NO_MAC",
            "iphone": "NOT_RUN_NO_DEVICE", "iosModelParity": "NOT_RUN", "iosPerformance": "NOT_RUN",
        }
        output = IOS / "docs/validation.json"; output.parent.mkdir(exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"PASS: {count} assets verified; iOS runtime not tested")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--resources-only", action="store_true")
    parser.add_argument("--syntax", action="store_true", help="Requires tree-sitter and tree-sitter-swift")
    args = parser.parse_args()
    validate(args.resources_only, args.syntax)
