# Translate Offline

An independent Android application built from Yomu's local inference pipeline.
It supports Japanese to English with CAT-Translate 1.4B Q5_K_M and Chinese to
English with Hy-MT2 1.8B i1-Q5_K_M. The installer contains both language models,
Japanese OCR, PP-OCRv6 Chinese/text detection, the bubble detector and dictionaries.

The `offline` module has no Internet permission, downloader, analytics or cloud
translation integration. Screen captures are held in memory. Models are copied
from the APK to private, backup-excluded storage and checked with SHA-256.
Only the selected language model is loaded; stop reading before switching source.

## Device and installation

Designed for the Samsung Galaxy S24 Ultra; Android 14 or newer is required.
The APK includes ARM64 native libraries. Keep roughly 6 GB free for installation,
model preparation and Android's temporary installation space.

Copy the APK by USB, open it on the phone and permit installation from the file
manager if prompted. Alternatively use `adb install -r offline-debug.apk`.
Launch Translate Offline and let model preparation finish. Select Japanese or
Chinese, choose page or continuous-scroll layout, and tap Start reading. Allow
display over other apps and approve screen capture when Android asks. Open your
reader/browser. The reader/browser can use its own network connection; translation
in this application runs locally.

Pause scrolling to translate automatically, or tap Translate. Hold Original to
reveal the original page. Drag the grip to move controls. Tap ×, Stop reading,
or the notification's Stop action to release capture and models.

## Rebuilding

Use JDK 17, Android SDK platform 36, NDK 26.1.10909125 or newer, and CMake 3.22.1.
Initialize `ml/llama.cpp` if working from Git: `git submodule update --init --recursive`.
The corresponding-source archive includes the pinned submodule sources.

1. Run `scripts/download-offline-vision.ps1 -Destination <vision-cache>` on the
   build computer. Downloads are pinned by repository revision and SHA-256.
   The checked-in dictionary contains 18,709 entries derived from the matching
   PP-OCRv6 recognition configuration (plus CTC blank gives 18,710 classes).
2. Place the two supplied GGUF files in Downloads, or call
   `scripts/prepare-offline-assets.ps1` with explicit `-CatModel` and `-HyModel`.
3. Run `scripts/build-offline.ps1 -JavaHome <jdk17> -SdkRoot <sdk> -VisionCache <vision-cache>`.
   It generates a local development signing key, prepares assets, runs unit tests
   and lint, and builds `offline/build/outputs/apk/debug/offline-debug.apk`.
   Preserve `.local/debug.keystore` privately to install compatible updates.

Models and signing keys are excluded from Git. Model preparation records the
actual byte sizes and checksums in `model-manifest.json`, included in the APK.
Network is used on the build computer for dependencies/model acquisition; the
installed app has no network capability through Android's Internet permission.

## Sources and adapted behavior

Yomu base: d29ee8e8353eeec3014f13a0b421ef19591c1699.
llama.cpp: 7fe450e19305b828c199d602c23a8337aaa1f03b.
Page color sampling and contrasting text color are adapted from
jedzqer/manga-translator-android b729828dc90b0840538c9e18f5ba12fcf00dfea9.
Its supplemental text-region approach informed the caption detector integration;
its cloud translation and network code are not included.
Application changes are distributed under AGPL-3.0; bundled model/runtime licences
are preserved in `offline/src/main/assets/licenses` and the in-app licence screen.

## Verification limits

Tests of decoding, layout, real-file model recovery and source-model contracts do
not establish translation accuracy on arbitrary manga or a fixed turnaround time.
Refer to the accompanying build report for actual device checks. The application
does not promise sub-400 ms translation. OCR can miss stylized, rotated or small
text; the supplemental detector reduces missed captions but is not full text
segmentation/inpainting. Lettering uses local background samples and fitted text.
Page-change checks continue during inference and while lettering is displayed.
Version 0.1.1 keeps the original source as the comparison baseline and masks the
actual screen areas occupied by English lettering, floating controls and system
bars. Small capture noise and a single transient frame do not clear a translation.
Gestures and viewport resize still invalidate immediately; programmatic page
changes are confirmed from two changed captures of the visible source area.
Programmatic content changes confined entirely to the masked translated boxes
cannot be detected visually until a gesture, resize or explicit Translate action.

## Version 0.1.1 flicker correction

Version 0.1.0 used an exact whole-frame hash and adopted a freshly captured English
frame as its baseline. One-level pixel rounding or the compositor delivering the
English overlay late could make it clear a completed translation and recall the
same cached translation repeatedly. Regression tests reproduce both failure paths.
Version 0.1.1 uses tolerant source-image comparison, excludes its own drawings and
changing system chrome, and confirms page movement before clearing. Controls and
painted regions are mapped from their actual screen positions. The update retains
the application ID and signing key, so it installs over version 0.1.0 and reuses
the existing verified model files.
