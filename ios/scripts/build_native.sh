#!/bin/bash
set -euo pipefail
IOS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$IOS_ROOT/.." && pwd)"
[[ "$(uname -s)" == Darwin ]] || { echo "Native iOS build requires macOS and Xcode." >&2; exit 1; }
for tool in cmake python3 xcodebuild xcrun; do command -v "$tool" >/dev/null || { echo "Missing prerequisite: $tool" >&2; exit 1; }; done
python3 "$IOS_ROOT/scripts/validate.py" --resources-only
BUILD_ROOT="$IOS_ROOT/.build/native"
mkdir -p "$BUILD_ROOT/headers/espeak-ng" "$IOS_ROOT/Native/Vendor"
cp "$REPO_ROOT/third_party/whisper.cpp-1.9.4/include/whisper.h" "$BUILD_ROOT/headers/"
cp "$REPO_ROOT/third_party/whisper.cpp-1.9.4/ggml/include/"*.h "$BUILD_ROOT/headers/"
cp "$REPO_ROOT/third_party/espeak-ng-1.52.0/src/include/espeak-ng/speak_lib.h" "$BUILD_ROOT/headers/espeak-ng/"
for slice in device-arm64 simulator-arm64 simulator-x86_64; do
    if [[ "$slice" == device-arm64 ]]; then sdk=iphoneos; arch=arm64; else sdk=iphonesimulator; arch="${slice#simulator-}"; fi
    build="$BUILD_ROOT/$slice"
    cmake -S "$IOS_ROOT/Native/Dependencies" -B "$build" -G Xcode \
        -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT="$sdk" -DCMAKE_OSX_ARCHITECTURES="$arch" \
        -DCMAKE_OSX_DEPLOYMENT_TARGET=17.0 -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED=NO
    cmake --build "$build" --config Release --target whisper espeak-ng --parallel 4
    # The dedicated dependency build contains only these library targets; no test/demo archives.
    libraries=()
    while IFS= read -r archive; do libraries+=("$archive"); done < <(find "$build" -type f -name '*.a' -path '*/Release*/*' | sort)
    [[ "${#libraries[@]}" -ge 4 ]] || { echo "Native archives missing in $build" >&2; exit 1; }
    xcrun libtool -static -o "$build/libShadowNativeDependencies.a" "${libraries[@]}"
done
xcrun lipo -create "$BUILD_ROOT/simulator-arm64/libShadowNativeDependencies.a" "$BUILD_ROOT/simulator-x86_64/libShadowNativeDependencies.a" -output "$BUILD_ROOT/libShadowNativeDependencies-simulator.a"
# Preserve previous output rather than deleting it; explicit paths stay inside Native/Vendor.
output="$IOS_ROOT/Native/Vendor/ShadowNativeDependencies.xcframework"
if [[ -e "$output" ]]; then mv "$output" "$output.backup-$(date +%Y%m%d%H%M%S)-$$"; fi
xcodebuild -create-xcframework \
    -library "$BUILD_ROOT/device-arm64/libShadowNativeDependencies.a" -headers "$BUILD_ROOT/headers" \
    -library "$BUILD_ROOT/libShadowNativeDependencies-simulator.a" -headers "$BUILD_ROOT/headers" \
    -output "$output"
echo "Built whisper.cpp 1.9.4 and eSpeak NG 1.52.0 for iOS 17 device + simulators."
