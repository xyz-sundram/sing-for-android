# <img src="imgs/icon.png" width="48" height="48" valign="middle" style="vertical-align: middle; margin-right: 8px;" alt="Sing for Android Icon" /> Sing for Android

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Platform](https://img.shields.io/badge/Platform-Android%2011%2B-green.svg)](https://android.com)
[![Telegram](https://img.shields.io/badge/Telegram-sundramsprojeckts-26A5E4?logo=telegram&logoColor=white)](https://t.me/sundramsprojeckts)

**Sing for Android** is an Xposed module that attempts to bring Apple Music's iOS-exclusive **Sing Mode** on the official Android app. It hooks into the Apple Music audio pipeline to isolate, attenuate, or boost vocals on the fly with low latency and hardware acceleration support.

Updates and discussion: [Sundram's Projects on Telegram](https://t.me/sundramsprojeckts).
This fork builds on [22cms/sing-for-android](https://github.com/22cms/sing-for-android).

---

## Screenshots

| Apple Music (Sing Active) | Settings Screen | Neural Model Catalog |
|:---:|:---:|:---:|
| <img src="imgs/screenshot_sing.png" width="240" alt="Apple Music Sing Mode" /> | <img src="imgs/screenshot_settings.png" width="240" alt="Settings Screen" /> | <img src="imgs/screenshot_models.png" width="240" alt="Model Selection" /> |

---

## Features

- **Real-Time On-Device Stem Separation**: Separates vocals from backing tracks in real time during audio playback entirely locally, using whatever hardware accelerator you think is the best to use.
- **Hardware Acceleration**:
  - **Qualcomm Hexagon HTP / NPU**: Powered by ONNX Runtime with Qualcomm QNN Execution Provider for super high speed real time processing with low thermals and battery drain.
  - **OpenCL / Vulkan GPU & NNAPI**: Powered by Alibaba MNN for cross-platform GPU acceleration and NNAPI support.
  - **Multi-threaded CPU Fallback**: High-performance portable fallback with configurable thread.
- **Multiple Model to choose from**:
  - **MDX-Net** (Kim Inst, UVR 1-9703) in FP16 / FP32 precision.
  - **Apple Sing (Vi-NNet)** native reference engine.
- **Advanced Playback & Slider Controls**:
  - **Two-Way Slider**: 0%–50% attenuates vocals for karaoke singing; 50%–100% attenuates the instrumental track for acapella vocal isolation.
  - **Bypass all client/server-side limitations**: Enable Sing on all songs, even those iOS will refuse to work with, and allow the slider to go all the way down & up.
  - **Preserve Side Channel**: Keep backing vocals, stereo reverbs, and vocoder vocal effects in the audio mix.
- **Modern Expressive UI**:
  - Material 3 Expressive and dynamic **Material You** theming adapting to your wallpaper palette.

---

## Requirements

- **Android 11** or higher. 
- **Apple Music for Android** (v7.0.0-beta 1607+).
- **Xposed Environment**:
  - [Vector](https://github.com/JingMatrix/Vector) (Rooted with Magisk / KernelSU / whatever)
  - Or JingMatrix's [LSPatch](https://github.com/JingMatrix/LSPatch) fork (Non-root, embedded module mode).

---

## Installation & Setup

1. **Install Module**:
   - Download an APK from this fork's [Releases](https://github.com/xyz-sundram/sing-for-android/releases), or [build it locally](docs/BUILDING.md).
   - Install the APK on your device.
2. **Enable in Xposed Manager**:
   - If using Vector: Open Vector, enable the **Sing for Android** module, and check **Apple Music** in its scope.
   - If using LSPatch: Patch the Apple Music APK with the Sing for Android module **in embedded mode** and with signature bypass mode 2.
3. **Configure Settings**:
   - Launch **Sing for Android** from your launcher (or long-press/right-click the Sing icon in Apple Music).
   - Select your preferred neural model and compute target in the app.
   - Run the built-in benchmark to verify that your hardware achieves realtime speeds (>1.0x Real-Time).

---

## Building from Source

See the [local build guide](docs/BUILDING.md) for Linux, macOS, Windows, and WSL
setup, optional MNN compilation, signing, tests, and manual releases.

With JDK 17 or 21 and the Android SDK packages installed:

```bash
git clone --recurse-submodules https://github.com/xyz-sundram/sing-for-android.git
cd sing-for-android
./gradlew :app:assembleDebug -PsingNdkVersion=28.0.13004108
```

On Windows, use `.\gradlew.bat` in place of `./gradlew`. APKs are written to
`app/build/outputs/apk/debug/`, including a universal APK and separate
`arm64-v8a`, `armeabi-v7a`, and `x86_64` APKs.

To build and patch Apple Music locally without root, follow the
[rootless patching guide](docs/ROOTLESS_PATCHING.md). The Python helper lets you
select the model and features, embeds Sing using JingMatrix LSPatch, and signs
an original APK or split archive. It produces the patched Apple Music APKs and
a matching companion; installation is a separate step.

Releases are built and published manually.

---

## Third-Party Projects & Acknowledgements

- **[Microsoft ONNX Runtime](https://github.com/microsoft/onnxruntime)** & **[Qualcomm QNN](https://www.qualcomm.com/products/technology/processors/cloud-ai-100/neural-processing-sdk)**: Neural network runtime with Hexagon HTP execution provider.
- **[Alibaba MNN](https://github.com/alibaba/MNN)**: Lightweight cross-platform deep learning inference engine for mobile GPUs and NPUs.
- **[MDX-Net / Ultimate Vocal Remover](https://github.com/Anjok07/ultimatevocalremovergui)**: State-of-the-art vocal isolation architectures by Roman Solovyev et al. (Kuielab).
- **[KissFFT](https://github.com/mborgerding/kissfft)**: Fast Fourier Transform library by Mark Borgerding.
- **[SpeexDSP](https://github.com/xiph/speexdsp)**: Streaming sample-rate conversion (BSD license).
- **[Vector](https://github.com/JingMatrix/Vector) / [LSPatch](https://github.com/JingMatrix/LSPatch)**: Modern ART hooking framework and patcher.
- **[Apple SF Symbols](https://developer.apple.com/sf-symbols/)**: Iconography reference.

---

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
Apple Music is a trademark of Apple Inc. This project is independent and not affiliated with or endorsed by Apple Inc.
