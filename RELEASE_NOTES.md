Shadow Reader 2.0.0-alpha3 is an ARM64 offline pronunciation feedback experiment.

The app performs acoustic inference on the phone with bundled model assets. It shows green/yellow/red/gray evidence by word and phoneme, estimated replay intervals, practice summaries, and an explicit experiment export. Scores remain uncalibrated; the colors are evidence categories, not probabilities or a validated pronunciation grade.

Assets:
- `ShadowReader-2.0.0-alpha3-offline-debug.apk` — 419 MB Android application APK.
- `ShadowReader-2.0.0-alpha3-source.zip` — application/native source, build scripts, notices, and validation report. It contains synthetic fixtures and no user recordings.
- `ShadowReader-2.0.0-alpha3-benchmark.apk` — optional synthetic-input instrumentation benchmark APK.

Build: JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1, Gradle 8.9. See `docs/offline-alpha3-validation.md` for offline asset restoration, build instructions, and the Redmi K80 checklist.

Validation: FP32 and dynamic INT8 parity passed on 120 public corpus items. Android tests passed 33 cases; one optional network integration test was skipped. Static ARM64, signature, asset hash, ELF 16 KiB and APK alignment checks passed. The Redmi K80 has not yet been connected for on-device latency, memory, installation-upgrade, or interaction testing.

License: GPL-3.0-or-later for the application distribution that includes eSpeak NG, with upstream licenses and notices for bundled third-party software and model resources. See the source archive for complete notices.
