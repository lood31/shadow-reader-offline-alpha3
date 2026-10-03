# Shadow Reader alpha3 — offline pronunciation experiment

ARM64 Android alpha build with on-device acoustic pronunciation evidence. The model, voice activity detector, word list and English phoneme resources ship inside the APK. Pronunciation output is experimental evidence; it is not calibrated as a probability or a clinical/educational score.

Install the APK from the GitHub release over an existing alpha2 installation signed with the matching key. APK size is about 419 MB. The signing private key is not published. First use unpacks and checks the bundled resources; no desktop backend is required for pronunciation analysis.

See [validation and build instructions](docs/offline-alpha3-validation.md). The 120 public-speech-corpus model parity check, Android unit tests and static APK checks passed. Redmi K80 timing, memory, upgrade-data preservation and interaction checks have not been run on device; the package includes a synthetic-input device benchmark.

The source distribution contains the app, native dependencies, build scripts and required notices. It excludes model weights and test recordings; the verified resources can be restored from the release APK with `scripts/restore-offline-assets.py`. Third-party components keep their own license terms. eSpeak NG is distributed under GPL-3.0-or-later; see the supplied license files.

## iPhone / iOS experimental client

Independent SwiftUI iOS 17+ source is in [ios/](ios/README.md). Cloud macOS compilation and simulator tests are defined in [GitHub Actions](.github/workflows/ios-macos.yml); model delivery uses a SHA-256 verified Release bundle. Windows static checks passed; Xcode, simulator and iPhone results are pending until real runs complete. This is a self-use experimental client, with no signed IPA or App Store distribution.
