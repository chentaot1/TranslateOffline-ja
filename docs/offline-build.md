# TranslateOffline-ja

An independent Android application built from Yomu's local inference pipeline.
It translates Japanese manga and on-screen text to English locally using the Fumeto 350M int8 ONNX encoder-decoder, YOLO26-nano speech bubble/panel detector, MangaOCR (`AR` and `NAR`), PP-OCRv6 DBNet scene text detection (`text_detection.onnx`), and SFX classifier.

The `offline` module has no Internet permission, downloader, analytics, or cloud translation integration. Screen captures are held only transiently in memory. Models are copied from the APK to private, backup-excluded storage and verified with SHA-256.

## Device and installation

Requires Android 12 or newer (API 31+) and a 64-bit ARM (`arm64-v8a`) device with $\ge 6\text{ GB}$ RAM recommended.
Keep roughly 3 GB free for APK installation and verified model extraction into app storage.

Copy the APK by USB, open it on the phone and permit installation from your file manager if prompted, or install via `adb install -r offline-japanese-debug.apk`.
Launch Translate Japanese and let local model preparation finish. Choose page or continuous-scroll layout, and tap Start reading. Allow Display over other apps and approve screen capture when Android asks. Open your reader, browser, or visual novel app; the underlying app can use its own network connection while translation runs locally on-device.

Pause scrolling to translate automatically, or tap Translate. Tap AR/NAR to toggle OCR modes. Hold Original to reveal the original page. Drag the grip to move controls. Tap ×, Stop reading, or the notification's Stop action to release capture and models.

## Rebuilding

Use JDK 17 and Android SDK platform 36 (no Android NDK or CMake toolchain is required — all runtime dependencies are pure Kotlin + Maven ONNX Runtime Android).

1. Place the pinned vision/OCR ONNX files and SFX filter model in a local cache directory (for example `scripts/download-offline-vision.ps1 -Destination <vision-cache>` fetches `text_detection.onnx` with pinned SHA-256 verification). Note: `prepare-offline-assets.ps1` accepts either `text_detection.onnx` or legacy `chinese_detection.onnx` in `-VisionCache` and packages it as `text_detection.onnx`.
2. Fumeto 350M int8 files (`encoder.int8.onnx`, `decoder.int8.onnx`, `enc-vocab.json`, `dec-vocab.json`, `meta.json`) are fetched automatically on first build if missing from the cache directory.
3. Run `scripts/build-offline.ps1 -JavaHome <jdk17> -SdkRoot <sdk> -VisionCache <vision-cache> -Language Japanese`.
   It generates a local development signing key, prepares assets, runs unit tests and lint, and builds `offline/build/outputs/apk/japanese/debug/offline-japanese-debug.apk`.
   Preserve `.local/debug.keystore` privately to install compatible updates over previous versions.

Models and signing keys are excluded from Git. Model preparation records the actual byte sizes and checksums in `model-manifest.json`, included in the APK. Network is used only on the build computer for dependencies/model acquisition; the installed APK has zero Internet permissions.

## Sources and adapted behavior

Yomu base: d29ee8e8353eeec3014f13a0b421ef19591c1699.
Page color sampling and contrasting text color are adapted from
jedzqer/manga-translator-android b729828dc90b0840538c9e18f5ba12fcf00dfea9.
Its supplemental text-region approach informed the caption detector integration;
its cloud translation and network code are not included.
Application changes are distributed under AGPL-3.0; bundled model/runtime licences
are preserved in `offline/src/main/assets/licenses` and the in-app licence screen.
