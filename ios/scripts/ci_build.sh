#!/bin/bash
set -euo pipefail
IOS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$IOS_ROOT"
mkdir -p .build/ci
xcodebuild -version
xcrun --sdk iphoneos --show-sdk-version
python3 scripts/validate.py --resources-only
python3 scripts/test_resource_tools.py
bash scripts/build_native.sh
pod install
# Preserve the real resolved dependency versions with the job artifacts.
cp Podfile.lock .build/ci/Podfile.lock
# Set test-process variables in the scheme, rather than assuming shell inheritance.
python3 - <<'PY'
import xml.etree.ElementTree as ET
from pathlib import Path
path = Path('ShadowReader.xcodeproj/xcshareddata/xcschemes/ShadowReader.xcscheme')
tree = ET.parse(path)
action = tree.getroot().find('TestAction')
variables = action.find('EnvironmentVariables')
if variables is None:
    variables = ET.SubElement(action, 'EnvironmentVariables')
ET.SubElement(variables, 'EnvironmentVariable', key='RUN_NATIVE_IOS_TESTS', value='1', isEnabled='YES')
tree.write(path, encoding='utf-8', xml_declaration=True)
PY
xcodebuild -workspace ShadowReader.xcworkspace -scheme ShadowReader \
    -configuration Release -destination 'generic/platform=iOS' \
    -derivedDataPath .build/ci/device CODE_SIGNING_ALLOWED=NO build \
    2>&1 | tee .build/ci/device-build.log
simulator_id="$(xcrun simctl list devices available --json | python3 -c 'import json,sys; d=json.load(sys.stdin); v=[x["udid"] for r,a in d["devices"].items() if ".iOS-" in r for x in a if x.get("isAvailable") and x["name"].startswith("iPhone")]; assert v,"No iPhone simulator installed"; print(v[0])')"
xcodebuild -workspace ShadowReader.xcworkspace -scheme ShadowReader \
    -configuration Debug -destination "platform=iOS Simulator,id=$simulator_id" \
    -parallel-testing-enabled NO -derivedDataPath .build/ci/simulator \
    -resultBundlePath .build/ci/Tests.xcresult CODE_SIGNING_ALLOWED=NO test \
    2>&1 | tee .build/ci/simulator-test.log
echo "Unsigned device compilation and simulator tests completed; physical-device acceptance is still pending."
