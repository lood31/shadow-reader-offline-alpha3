import java.io.File
import java.security.MessageDigest
import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.shadowreader.app"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "com.shadowreader.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "2.0.0-alpha3"
        val offline = providers.gradleProperty("offlinePronunciation").isPresent
        buildConfigField("boolean", "OFFLINE_PRONUNCIATION", offline.toString())
        ndk { abiFilters += if (offline) listOf("arm64-v8a") else listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        if (!providers.gradleProperty("skipNative").isPresent) {
            externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DSHADOW_OFFLINE=${if (offline) "ON" else "OFF"}") } }
        }
        testInstrumentationRunner = "com.shadowreader.app.OfflineBenchmark"
    }
    buildFeatures { compose = true; buildConfig = true }
    if (providers.gradleProperty("offlinePronunciation").isPresent) {
        sourceSets.getByName("main").assets.srcDir("src/offline/assets")
    }
    androidResources { noCompress += "onnx" }
    if (!providers.gradleProperty("skipNative").isPresent) {
        externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    }
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.layout.projectDirectory.file(".tools/debug.keystore").asFile
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
val generateDebugKeystore = tasks.register<Exec>("generateDebugKeystore") {
    val keystore = rootProject.layout.projectDirectory.file(".tools/debug.keystore").asFile
    outputs.file(keystore)
    onlyIf { !keystore.exists() }
    doFirst { keystore.parentFile.mkdirs() }
    val keytool = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
    commandLine(
        File(System.getProperty("java.home"), "bin/$keytool").absolutePath,
        "-genkeypair", "-keystore", keystore.absolutePath,
        "-storepass", "android", "-keypass", "android", "-alias", "androiddebugkey",
        "-dname", "CN=Android Debug,O=Shadow Reader,C=US", "-keyalg", "RSA",
        "-keysize", "2048", "-validity", "10000",
    )
}
tasks.configureEach { if (name == "validateSigningDebug") dependsOn(generateDebugKeystore) }
// skipNative is only for JVM/lint iteration while installing the NDK, never for delivery.
tasks.configureEach {
    if (name.startsWith("package") || name.startsWith("assemble")) doFirst {
        check(!providers.gradleProperty("skipNative").isPresent) { "APK builds require the Whisper native library; remove -PskipNative." }
    }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

if (providers.gradleProperty("offlinePronunciation").isPresent) {
    val verifyOfflineAssets = tasks.register("verifyOfflineAssets") {
        doLast {
            fun hash(file: File): String {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer,0,n) }
                }
                return digest.digest().joinToString("") { "%02x".format(it) }
            }
            val parser = JsonSlurper()
            val receipt = parser.parse(rootProject.file("backend/models/offline/export-manifest.json")) as Map<*,*>
            check(receipt["validationStatus"] == "PASS") { "Offline model parity gate has not passed." }
            val root = file("src/offline/assets/pronunciation")
            val manifest = parser.parse(File(root,"manifest.json")) as Map<*,*>
            check(manifest["validationReportSha256"] == receipt["validationReportSha256"])
            check(hash(rootProject.file("backend/runs/offline-parity/report.json")) == receipt["validationReportSha256"])
            val files = manifest["files"] as Map<*,*>
            files.forEach { (name,value) ->
                val asset = File(root,name.toString()); val spec = value as Map<*,*>
                check(asset.canonicalPath.startsWith(root.canonicalPath+File.separator))
                check(asset.length() == (spec["bytes"] as Number).toLong() && hash(asset) == spec["sha256"]) { "Corrupt bundled asset: $name" }
            }
        }
    }
    tasks.named("preBuild") { dependsOn(verifyOfflineAssets) }
}
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
