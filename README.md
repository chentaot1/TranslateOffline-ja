<div align="center">

# ⚡ TranslateOffline-ja

**100% Offline, Zero-Network Japanese Manga & Screen Translator for Android.**  
Real-time speech bubble detection, autoregressive Japanese OCR, and neural translation running 100% on-device in ~300ms.

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2012%2B%20(API%2031%2B)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Architecture-ARM64--v8a-E4572E)](https://en.wikipedia.org/wiki/AArch64)
[![Zero Permissions](https://img.shields.io/badge/android.permission.INTERNET-0%20(Offline)-brightgreen.svg)](#privacy--zero-network-guarantee)
[![Release](https://img.shields.io/badge/Download-v1.0.0%20APK-orange.svg)](https://github.com/chentaot1/TranslateOffline-ja/releases/latest)

</div>

---

## 🌟 Why TranslateOffline-ja?

Most translation tools for Android rely on cloud APIs, require \$10–\$20/month subscriptions, send your personal screen captures to remote servers, or take 12–18 seconds per page with thermal-throttling local LLMs.

**TranslateOffline-ja** runs entirely on your phone's CPU with bundled, quantized ONNX models:

* **🔒 Zero Network Permissions (`INTERNET = 0`):** The app manifest does not request internet access. It is physically impossible for the app to send data anywhere. Works completely offline on flights, subways, or off-grid.
* **⚡ Blazing Fast Latency (~300ms):** Powered by the **Fumeto 350M int8** encoder-decoder translation engine running locally on ARM NEON CPU cores. No 15-second LLM streaming pauses.
* **📖 Built for Raw Manga & Screens:**
  * **Vertical & Horizontal Japanese OCR:** Autoregressive MangaOCR handles vertical text, furigana, and stylized comic lettering, alongside fast non-autoregressive mode (`NAR`).
  * **Full Dialogue & Commentary Coverage:** Captures dialogues, margin notes, vertical commentary, and tsukkomi down to $12 \times 12\text{ px}$.
  * **Sound Effect (SFX) Translation:** Built-in SFX classifier renders translated sound effects with translucent typography over artwork.
  * **Recursive Gutter Reading Order:** Decomposes manga panels right-to-left and top-to-bottom directly from drawn page gutters.
* **🖤 AMOLED Pitch-Black Overlays:** Pure `#000000` text backgrounds shut off OLED subpixels on modern AMOLED displays for maximum contrast and zero reading glare.
* **📱 Works Over Any Screen:** Floating overlay UI works over manga readers, web browsers, visual novels, e-readers, or image viewers (Chrome, Mihon, Tachiyomi, Shonen Jump, Kindle, etc.).

---

## 📦 Download & Installation

1. Download the latest release APK from [**GitHub Releases**](https://github.com/chentaot1/TranslateOffline-ja/releases/latest):
   * **`TranslateOffline-ja-arm64-v1.0.0.apk`** (~1.01 GiB, includes all 12 bundled offline ONNX models).
2. Install the APK on your device:
   * **OS:** Android 12+ (API 31+), 64-bit ARM (`arm64-v8a`).
   * **RAM:** $\ge 6\text{ GB}$ recommended (models keep ~1.0 GiB of weights resident in RAM while the reading service is active; 4 GB devices may experience background termination under memory pressure).
3. Open the app and grant the two required Android permissions:
   * **Display Over Other Apps** (for the floating reading button and translation overlay).
   * **MediaProjection** (for reading screen content locally when you tap the button).
4. Tap **Start reading**. Open your manga reader, browser, or visual novel and tap **Translate** (or scroll and pause in automatic mode) to translate!

---

## 🛠️ Architecture & ML Pipeline

```
  Screen Capture (MediaProjection)
                │
                ▼
      YOLO26-nano Detector  ───►  Speech Balloons & Panel Frames
                │
                ▼
        DBNet Scene Finder   ───►  Supplemental Text (Notes & Unenclosed)
                │
                ▼
     Autoregressive MangaOCR ───►  Japanese Text Extraction (AR / Fast NAR)
                │
                ▼
       SFX Filter Engine    ───►  Sound Effect Match & Dictionary
                │
                ▼
       Context Assembler    ───►  Recursive Physical Gutter Reading Order
                │
                ▼
       Fumeto 350M int8     ───►  ModernBERT-ja 25L Encoder + 4L Decoder
                │
                ▼
       AMOLED Typesetter    ───►  In-Place Typography & Subpixel Black Fill
```

---

## 📜 Third-Party Licenses & Disclosures

TranslateOffline-ja is licensed under the **GNU Affero General Public License version 3 (AGPL-3.0)**.

This project incorporates and builds upon the following open-source works:

| Component | Upstream Author / Project | License | Usage |
| :--- | :--- | :--- | :--- |
| **Pipeline & Baseline** | [Yomu](https://github.com/artsaraiva/yomu) by artsaraiva | **AGPL-3.0** | Pipeline abstractions, typesetting, and OCR decoding base. |
| **Color Matching** | [manga-translator-android](https://github.com/jedzqer/manga-translator-android) by jedzqer | **AGPL-3.0** | Page background sampling and high-contrast text color logic. |
| **Layout & Bubble Detector** | [manga-panel-detector-yolo26n-onnx](https://huggingface.co/mednasserallah/manga-panel-detector-yolo26n-onnx) by mednasserallah (Ultralytics YOLO26) | **AGPL-3.0** | Dual-class panel and speech bubble layout detection. |
| **Fumeto 350M MT** | [fumetotranslate-manga-ja-translator](https://huggingface.co/fumetodev/fumetotranslate-manga-ja-translator) by fumetodev / [ModernBERT-ja](https://huggingface.co/lightonai/modernbert-ja) (AnswerDotAI / LightOn) | **Apache-2.0** | 25-layer ModernBERT encoder + 4-layer 16k-vocab decoder in int8 ONNX. |
| **Japanese OCR** | [MangaOCR](https://github.com/kha-white/manga-ocr) by kha-white / jzhang533 | **Apache-2.0** | Vision encoder-decoder models (`AR` and `NAR`) for Japanese comic text. |
| **Supplemental OCR** | [PaddlePaddle PP-OCRv6](https://github.com/PaddlePaddle/PaddleOCR) | **Apache-2.0** | DBNet scene text detection (`text_detection.onnx`) for un-enclosed side text. |
| **Inference Runtime** | [ONNX Runtime Android](https://github.com/microsoft/onnxruntime) (Microsoft) | **MIT** | On-device model execution and ARM NEON CPU tensor operations. |

All full license texts are preserved under [`offline/src/main/assets/licenses/`](offline/src/main/assets/licenses/) and bundled in the distributed application.

---

## 🔒 Privacy & Zero-Network Guarantee

- **Zero Network Code:** The Android permission `android.permission.INTERNET` is completely absent.
- **Zero Disk Logging:** Screen frames captured during translation exist only in transient memory (`Bitmap`) and are recycled immediately after rendering.
- **Self-Contained:** All neural weights, tokenizers, and dictionaries are stored locally within private application storage.

---

## 🔨 Building from Source

Prerequisites:
- JDK 17
- Android SDK 34–36 with build-tools 34.0.0
- Local ONNX model cache bundle

Run the automated offline build script:
```powershell
powershell.exe -ExecutionPolicy Bypass -File scripts\build-offline.ps1 `
    -JavaHome "<path-to-jdk-17>" `
    -SdkRoot "<path-to-android-sdk>" `
    -VisionCache "<path-to-bundled-models>" `
    -GradleCache "<path-to-gradle-cache>" `
    -Language Japanese `
    -SkipLint
```

Output APK will be generated at:
`offline/build/outputs/apk/japanese/debug/offline-japanese-debug.apk`
