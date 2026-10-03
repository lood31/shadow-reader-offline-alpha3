"""Generate a deterministic, reviewable Xcode project on Windows or macOS. No XcodeGen required."""
import hashlib
import json
import plistlib
from pathlib import Path
from xml.sax.saxutils import escape

IOS = Path(__file__).resolve().parents[1]
objects = {}

def ident(name):
    return hashlib.sha256(name.encode()).hexdigest()[:24].upper()

def node(node_key, isa, **attributes):
    key = ident(node_key)
    objects[key] = dict(isa=isa, **attributes)
    return key

def render(value, indent=0):
    if isinstance(value, dict):
        return "{\n" + "".join("\t"*(indent+1)+json.dumps(str(k))+" = "+render(v, indent+1)+";\n" for k, v in value.items()) + "\t"*indent + "}"
    if isinstance(value, list):
        return "(" + ", ".join(render(v, indent) for v in value) + ("," if value else "") + ")"
    return json.dumps(str(value), ensure_ascii=False)

def reference(path, kind=None, tree="SOURCE_ROOT"):
    suffix = Path(path).suffix
    file_type = kind or {".swift": "sourcecode.swift", ".mm": "sourcecode.cpp.objcpp", ".h": "sourcecode.c.h", ".plist": "text.plist.xml", ".entitlements": "text.plist.entitlements", ".xcframework": "wrapper.xcframework", ".framework": "wrapper.framework", ".tbd": "sourcecode.text-based-dylib-definition"}.get(suffix, "text")
    return node("file:"+path, "PBXFileReference", path=path, sourceTree=tree, lastKnownFileType=file_type)

def build_file(name, file_id=None, product=None, settings=None):
    attributes = {"fileRef": file_id} if file_id else {"productRef": product}
    if settings:
        attributes["settings"] = settings
    return node("build:"+name, "PBXBuildFile", **attributes)

def phase(phase_key, isa, files, **attributes):
    return node("phase:"+phase_key, isa, buildActionMask=2147483647, files=files, runOnlyForDeploymentPostprocessing=0, **attributes)

def configs(name, settings):
    ids = []
    for config in ("Debug", "Release"):
        values = dict(settings)
        values["SWIFT_OPTIMIZATION_LEVEL"] = "-Onone" if config == "Debug" else "-O"
        if config == "Debug":
            values["ENABLE_TESTABILITY"] = "YES"
            values["DEBUG_INFORMATION_FORMAT"] = "dwarf"
            values["SWIFT_ACTIVE_COMPILATION_CONDITIONS"] = "DEBUG $(inherited)"
        ids.append(node(f"config:{name}:{config}", "XCBuildConfiguration", name=config, buildSettings=values))
    return node("configs:"+name, "XCConfigurationList", buildConfigurations=ids, defaultConfigurationIsVisible=0, defaultConfigurationName="Release")

def write_plist(path, payload):
    destination = IOS / path
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(plistlib.dumps(payload, sort_keys=False))

def generate():
    objects.clear()
    app_sources = sorted(p.relative_to(IOS).as_posix() for p in (IOS / "ShadowReader").rglob("*.swift")) + ["Shared/ShareInbox.swift", "Native/SRNativeEngine.mm"]
    share_sources = ["Shared/ShareInbox.swift", "ShareExtension/ShareViewController.swift"]
    test_sources = sorted(p.relative_to(IOS).as_posix() for p in (IOS / "Tests").glob("*.swift"))
    files = {p: reference(p) for p in sorted(set(app_sources+share_sources+test_sources))}
    for path in ["Native/SRNativeEngine.h", "Native/ShadowReader-Bridging-Header.h", "Config/App-Info.plist", "Config/Share-Info.plist", "Config/App.entitlements", "Config/Share.entitlements"]:
        files[path] = reference(path)
    assets = reference("Resources/pronunciation", "folder")
    receipt = reference("Resources/resource-receipt.json")
    fixtures = reference("Tests/Fixtures", "folder")
    native = reference("Native/Vendor/ShadowNativeDependencies.xcframework")
    sqlite = reference("usr/lib/libsqlite3.tbd", tree="SDKROOT")
    accelerate = reference("System/Library/Frameworks/Accelerate.framework", tree="SDKROOT")
    app_product = node("product:app", "PBXFileReference", explicitFileType="wrapper.application", path="ShadowReader.app", sourceTree="BUILT_PRODUCTS_DIR")
    share_product = node("product:share", "PBXFileReference", explicitFileType="wrapper.app-extension", path="ShadowReaderShare.appex", sourceTree="BUILT_PRODUCTS_DIR")
    test_product = node("product:tests", "PBXFileReference", explicitFileType="wrapper.cfbundle", path="ShadowReaderTests.xctest", sourceTree="BUILT_PRODUCTS_DIR")
    packages, package_products = [], []
    for name, url, version in [("SwiftSoup", "https://github.com/scinfu/SwiftSoup.git", "2.6.0"), ("ZIPFoundation", "https://github.com/weichsel/ZIPFoundation.git", "0.9.19")]:
        package = node("package:"+name, "XCRemoteSwiftPackageReference", repositoryURL=url, requirement={"kind": "exactVersion", "version": version})
        packages.append(package)
        package_products.append(node("package-product:"+name, "XCSwiftPackageProductDependency", package=package, productName=name))
    app_phase = phase("app:sources", "PBXSourcesBuildPhase", [build_file("app:"+p, files[p]) for p in app_sources])
    app_resources = phase("app:resources", "PBXResourcesBuildPhase", [build_file("app:assets", assets), build_file("app:receipt", receipt)])
    app_frameworks = phase("app:frameworks", "PBXFrameworksBuildPhase", [build_file("app:native", native), build_file("app:sqlite", sqlite), build_file("app:accelerate", accelerate)]+[build_file("app:package:"+p, product=p) for p in package_products])
    share_phase = phase("share:sources", "PBXSourcesBuildPhase", [build_file("share:"+p, files[p]) for p in share_sources])
    share_resources = phase("share:resources", "PBXResourcesBuildPhase", [])
    share_frameworks = phase("share:frameworks", "PBXFrameworksBuildPhase", [])
    tests_phase = phase("tests:sources", "PBXSourcesBuildPhase", [build_file("tests:"+p, files[p]) for p in test_sources])
    tests_resources = phase("tests:resources", "PBXResourcesBuildPhase", [build_file("tests:fixtures", fixtures)])
    tests_frameworks = phase("tests:frameworks", "PBXFrameworksBuildPhase", [])
    common = dict(SWIFT_VERSION="5.0", IPHONEOS_DEPLOYMENT_TARGET="17.0", SDKROOT="iphoneos", TARGETED_DEVICE_FAMILY="1,2", CODE_SIGN_STYLE="Automatic", DEVELOPMENT_TEAM="", SHADOW_APP_GROUP="group.com.shadowreader.ios", CLANG_ENABLE_MODULES="YES", CLANG_ENABLE_OBJC_ARC="YES", CLANG_CXX_LANGUAGE_STANDARD="c++17", CLANG_CXX_LIBRARY="libc++", GCC_ENABLE_CPP_EXCEPTIONS="YES", GENERATE_INFOPLIST_FILE="NO", CURRENT_PROJECT_VERSION="1", MARKETING_VERSION="2.0.0")
    app_settings = dict(common, PRODUCT_NAME="ShadowReader", PRODUCT_BUNDLE_IDENTIFIER="com.shadowreader.ios", INFOPLIST_FILE="Config/App-Info.plist", CODE_SIGN_ENTITLEMENTS="Config/App.entitlements", SWIFT_OBJC_BRIDGING_HEADER="Native/ShadowReader-Bridging-Header.h", HEADER_SEARCH_PATHS=["$(inherited)", "$(SRCROOT)/Native", "$(SRCROOT)/Native/Vendor/ShadowNativeDependencies.xcframework/ios-arm64/Headers"], LD_RUNPATH_SEARCH_PATHS=["$(inherited)", "@executable_path/Frameworks"])
    app_settings["HEADER_SEARCH_PATHS[sdk=iphonesimulator*]"] = ["$(inherited)", "$(SRCROOT)/Native", "$(SRCROOT)/Native/Vendor/ShadowNativeDependencies.xcframework/ios-arm64_x86_64-simulator/Headers"]
    share_settings = dict(common, PRODUCT_NAME="ShadowReaderShare", PRODUCT_BUNDLE_IDENTIFIER="com.shadowreader.ios.share", INFOPLIST_FILE="Config/Share-Info.plist", CODE_SIGN_ENTITLEMENTS="Config/Share.entitlements", APPLICATION_EXTENSION_API_ONLY="YES", SKIP_INSTALL="YES", LD_RUNPATH_SEARCH_PATHS=["$(inherited)", "@executable_path/Frameworks", "@executable_path/../../Frameworks"])
    test_settings = dict(common, PRODUCT_NAME="ShadowReaderTests", PRODUCT_BUNDLE_IDENTIFIER="com.shadowreader.ios.tests", GENERATE_INFOPLIST_FILE="YES", TEST_HOST="$(BUILT_PRODUCTS_DIR)/ShadowReader.app/ShadowReader", BUNDLE_LOADER="$(TEST_HOST)")
    app_id, share_id, test_id = ident("target:app"), ident("target:share"), ident("target:tests")
    proxy = node("proxy:share", "PBXContainerItemProxy", containerPortal=ident("project"), proxyType=1, remoteGlobalIDString=share_id, remoteInfo="ShadowReaderShare")
    share_dep = node("dep:share", "PBXTargetDependency", target=share_id, targetProxy=proxy)
    test_proxy = node("proxy:app", "PBXContainerItemProxy", containerPortal=ident("project"), proxyType=1, remoteGlobalIDString=app_id, remoteInfo="ShadowReader")
    test_dep = node("dep:app", "PBXTargetDependency", target=app_id, targetProxy=test_proxy)
    embed = phase("app:embed-share", "PBXCopyFilesBuildPhase", [build_file("app:embed", share_product, settings={"ATTRIBUTES": ["RemoveHeadersOnCopy"]})], dstPath="", dstSubfolderSpec=13, name="Embed App Extensions")
    node("target:app", "PBXNativeTarget", name="ShadowReader", productName="ShadowReader", productReference=app_product, productType="com.apple.product-type.application", buildConfigurationList=configs("app", app_settings), buildPhases=[app_phase, app_frameworks, app_resources, embed], dependencies=[share_dep], buildRules=[], packageProductDependencies=package_products)
    node("target:share", "PBXNativeTarget", name="ShadowReaderShare", productName="ShadowReaderShare", productReference=share_product, productType="com.apple.product-type.app-extension", buildConfigurationList=configs("share", share_settings), buildPhases=[share_phase, share_frameworks, share_resources], dependencies=[], buildRules=[])
    node("target:tests", "PBXNativeTarget", name="ShadowReaderTests", productName="ShadowReaderTests", productReference=test_product, productType="com.apple.product-type.bundle.unit-test", buildConfigurationList=configs("tests", test_settings), buildPhases=[tests_phase, tests_frameworks, tests_resources], dependencies=[test_dep], buildRules=[])
    products = node("group:products", "PBXGroup", name="Products", sourceTree="<group>", children=[app_product, share_product, test_product])
    group = node("group:root", "PBXGroup", sourceTree="<group>", children=list(files.values())+[assets, receipt, fixtures, native, sqlite, accelerate, products])
    project = node("project", "PBXProject", buildConfigurationList=configs("project", dict(CLANG_WARN_DOCUMENTATION_COMMENTS="YES", SWIFT_VERSION="5.0", IPHONEOS_DEPLOYMENT_TARGET="17.0", SDKROOT="iphoneos")), compatibilityVersion="Xcode 14.0", developmentRegion="en", knownRegions=["en", "zh-Hans", "Base"], mainGroup=group, productRefGroup=products, projectDirPath="", projectRoot="", targets=[app_id, share_id, test_id], packageReferences=packages, attributes={"LastUpgradeCheck": "1600", "BuildIndependentTargetsInParallel": "YES", "TargetAttributes": {app_id: {"CreatedOnToolsVersion": "16.0"}, share_id: {"CreatedOnToolsVersion": "16.0"}, test_id: {"CreatedOnToolsVersion": "16.0", "TestTargetID": app_id}}})
    document = dict(archiveVersion=1, classes={}, objectVersion=56, objects=objects, rootObject=project)
    destination = IOS / "ShadowReader.xcodeproj"
    destination.mkdir(exist_ok=True)
    (IOS / "Config").mkdir(exist_ok=True)
    (destination / "project.pbxproj").write_text("// !$*UTF8*$!\n"+render(document)+"\n", encoding="utf-8")
    (IOS / "Config/project-graph.json").write_text(json.dumps(document, indent=2)+"\n", encoding="utf-8")
    basic = {"CFBundleDevelopmentRegion": "$(DEVELOPMENT_LANGUAGE)", "CFBundleExecutable": "$(EXECUTABLE_NAME)", "CFBundleIdentifier": "$(PRODUCT_BUNDLE_IDENTIFIER)", "CFBundleInfoDictionaryVersion": "6.0", "CFBundleName": "$(PRODUCT_NAME)", "CFBundleShortVersionString": "$(MARKETING_VERSION)", "CFBundleVersion": "$(CURRENT_PROJECT_VERSION)", "ShadowAppGroup": "$(SHADOW_APP_GROUP)"}
    write_plist("Config/App-Info.plist", dict(basic, CFBundlePackageType="APPL", CFBundleDisplayName="影子阅读器", NSMicrophoneUsageDescription="用于手动录制英语跟读；录音和识别在设备本地处理。", UILaunchScreen={}, UISupportedInterfaceOrientations=["UIInterfaceOrientationPortrait", "UIInterfaceOrientationLandscapeLeft", "UIInterfaceOrientationLandscapeRight"], **{"UISupportedInterfaceOrientations~ipad": ["UIInterfaceOrientationPortrait", "UIInterfaceOrientationPortraitUpsideDown", "UIInterfaceOrientationLandscapeLeft", "UIInterfaceOrientationLandscapeRight"]}))
    write_plist("Config/Share-Info.plist", dict(basic, CFBundlePackageType="XPC!", CFBundleDisplayName="影子阅读器", NSExtension={"NSExtensionPointIdentifier": "com.apple.share-services", "NSExtensionPrincipalClass": "$(PRODUCT_MODULE_NAME).ShareViewController", "NSExtensionAttributes": {"NSExtensionActivationRule": {"NSExtensionActivationSupportsText": True, "NSExtensionActivationSupportsWebURLWithMaxCount": 1}}}))
    for name in ("App", "Share"):
        write_plist("Config/"+name+".entitlements", {"com.apple.security.application-groups": ["$(SHADOW_APP_GROUP)"]})
    def buildable(key, name):
        return f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{key}" BuildableName="{escape(name)}" BlueprintName="{escape(name.split(".")[0])}" ReferencedContainer="container:ShadowReader.xcodeproj"/>'
    app_ref, test_ref = buildable(app_id, "ShadowReader.app"), buildable(test_id, "ShadowReaderTests.xctest")
    scheme = f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1600" version="1.3">
 <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{app_ref}</BuildActionEntry><BuildActionEntry buildForTesting="YES" buildForRunning="NO" buildForProfiling="NO" buildForArchiving="NO" buildForAnalyzing="YES">{test_ref}</BuildActionEntry></BuildActionEntries></BuildAction>
 <TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables><TestableReference skipped="NO">{test_ref}</TestableReference></Testables><MacroExpansion>{app_ref}</MacroExpansion></TestAction>
 <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{app_ref}</BuildableProductRunnable></LaunchAction>
 <ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">{app_ref}</BuildableProductRunnable></ProfileAction>
 <AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>
'''
    scheme_path = destination / "xcshareddata/xcschemes"
    scheme_path.mkdir(parents=True, exist_ok=True)
    (scheme_path / "ShadowReader.xcscheme").write_text(scheme, encoding="utf-8")
    print(f"Generated {destination.name}: {len(app_sources)} app sources, {len(share_sources)} extension sources, {len(test_sources)} test sources")

if __name__ == "__main__":
    generate()
