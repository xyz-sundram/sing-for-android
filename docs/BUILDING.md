# Build Sing for Android locally

This guide builds the Sing module and companion app from source. For a patched
Apple Music APK or split set that works without root, complete the build setup
here, then follow [Rootless patching](ROOTLESS_PATCHING.md).

Updates and discussion: [Sundram's Projects on Telegram](https://t.me/sundramsprojeckts).

## Requirements

| Tool | Version / purpose |
| --- | --- |
| Git | Clone the project and its native submodules. |
| JDK | **17 or 21** for Gradle; use **21** if you will also run LSPatch. Set `JAVA_HOME` and put its `bin` directory on `PATH`. |
| Android SDK command-line tools or Android Studio | Install the SDK packages below. |
| Android SDK Platform | **34** (`compileSdk` and `targetSdk`). |
| Android SDK Build Tools | **34.0.0** for the app; also install **35.0.0** for the rootless patcher. |
| Android NDK | Examples use **28.0.13004108** (r28) through `-PsingNdkVersion`. |
| CMake | **3.22.1**, installed through the Android SDK manager. |
| Python | **3.10+**, only for rootless patching and the patcher tests; no third-party Python packages are needed. |
| Android Platform Tools | `adb`, for installation and device testing. |

Use the checked-in Gradle wrapper, which selects Gradle **8.7**. You do not need
a separate Gradle installation. JDK versions newer than 21 are outside this
guide's supported setup. The first build needs internet access for Gradle,
Maven dependencies, and native sources.

## 1. Clone the source

```bash
git clone --recurse-submodules https://github.com/xyz-sundram/sing-for-android.git
cd sing-for-android
```

For an existing checkout:

```bash
git submodule update --init --recursive
```

`extern/kissfft` and `extern/speexdsp` must contain their source files before
building. Keep the submodules at the commits recorded by this repository.

## 2. Install and locate the Android SDK

In Android Studio, open **SDK Manager**. Install Android SDK Platform 34,
Platform Tools, Build Tools 34.0.0 and 35.0.0, NDK 28.0.13004108, and CMake
3.22.1. Enable **Show Package Details** to select exact tool versions.

With the command-line tools on `PATH`, the equivalent command is:

```bash
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" "build-tools;35.0.0" "ndk;28.0.13004108" "cmake;3.22.1"
sdkmanager --licenses
```

On Windows, the command may be named `sdkmanager.bat`.

Set `ANDROID_HOME` to your SDK directory. Typical locations are:

| Host | SDK location |
| --- | --- |
| Linux | `$HOME/Android/Sdk` |
| macOS | `$HOME/Library/Android/sdk` |
| Windows | `%LOCALAPPDATA%\Android\Sdk` |

For Linux, for example:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
java -version
```

For Windows PowerShell:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"
java -version
```

Alternatively, create an untracked `local.properties` in the project root:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

Use forward slashes in Windows properties paths, for example
`sdk.dir=C:/Users/you/AppData/Local/Android/Sdk`.

Under WSL, use a Linux JDK, SDK, and NDK. When using Windows Python or Gradle,
use the Windows tools. In Android Studio, set **Gradle JDK** to the same JDK
you use in the terminal.

## 3. Build a debug APK

From the repository root on Linux, macOS, or WSL:

```bash
./gradlew :app:assembleDebug -PsingNdkVersion=28.0.13004108
```

On Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug -PsingNdkVersion=28.0.13004108
```

The project defaults to NDK `28.0.12674087`. Passing `singNdkVersion` selects
the installed r28 release used by this guide. To avoid repeating the flag,
add `singNdkVersion=28.0.13004108` to your local Gradle user properties file
(`~/.gradle/gradle.properties`). Setting `ANDROID_NDK_HOME` alone does not
override the app's Gradle NDK selection.

Outputs are in `app/build/outputs/apk/debug/`:

- `app-universal-debug.apk`: contains all three supported architectures.
- `app-arm64-v8a-debug.apk`: most current Android phones.
- `app-armeabi-v7a-debug.apk`: 32-bit ARM devices.
- `app-x86_64-debug.apk`: compatible x86-64 devices and emulators; the native
  engine is compiled with AVX2/FMA and requires matching CPU support.

The basic build includes the native engine and ONNX integration. Model weights
and ONNX/QNN native runtimes download through the companion app when needed;
they are not bundled with the APK. MNN is optional and requires the next step.

## 4. Optional: include MNN

Build MNN before assembling Sing to enable MNN models and its GPU/NNAPI
backends. The helper uses Bash, Git, CMake, and `nproc`; run it on Linux or WSL
with the matching Android NDK installed. On macOS, GNU `nproc` is available
through GNU coreutils; use its `gnubin` directory on `PATH` for this script.

```bash
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"
export PATH="$ANDROID_HOME/cmake/3.22.1/bin:$PATH"

# Build all three architectures, or pass only the ones you need.
bash scripts/build_mnn.sh
# Example: bash scripts/build_mnn.sh arm64-v8a

./gradlew :app:assembleDebug -PsingNdkVersion=28.0.13004108
```

The script clones Alibaba MNN into `build-mnn/MNN`, applies the local
NNAPI/QNN patches, prepares QNN dependencies where supported, and copies
`libMNN.so` into `app/src/main/jniLibs/<abi>/`. These binaries are ignored by
Git. Only the architectures you build receive MNN support. The helper clones
the current MNN default branch on its first run and reuses that checkout;
record `git -C build-mnn/MNN rev-parse HEAD` when preparing a release.

## 5. Build and sign a release APK

For a local release build:

```bash
./gradlew :app:assembleRelease -PsingNdkVersion=28.0.13004108
```

Outputs are in `app/build/outputs/apk/release/`, with names such as
`app-universal-release.apk` and `app-arm64-v8a-release.apk`. Release builds
enable code and resource shrinking.

**Without `keystore.properties`, both debug and release builds use the local
Android debug key.** Configure a private key before publishing a standalone
Sing release. To create one, run the following with your own private path and
answer the password and certificate prompts:

```bash
keytool -genkeypair -keystore /private/path/sing-release.jks -storetype JKS -alias sing-release -keyalg RSA -keysize 3072 -validity 10000
```

Create `keystore.properties` in the project root:

```properties
storeFile=/private/path/sing-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=sing-release
keyPassword=YOUR_KEY_PASSWORD
```

On Windows, use a path such as `C:/Users/you/keys/sing-release.jks`.
`keystore.properties`, `*.jks`, and `*.keystore` are ignored by Git. Keep a
private backup of the key and passwords: updates require the same signing
identity. With this file present, the project also uses that key for debug
builds.

Rebuild the release and verify the APK with the SDK's `apksigner`:

```bash
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs app/build/outputs/apk/release/app-universal-release.apk
```

The rootless patcher re-signs its configured companion and all Apple Music
splits with its own `.patcher/signing.jks`. Signing the standalone module
does not change that key. See [Rootless patching](ROOTLESS_PATCHING.md) for
the helper's personal local signing defaults and update requirements.

## 6. Run checks and install

```bash
python3 -m unittest discover -s scripts/tests -v
./gradlew :app:testDebugUnitTest -PsingNdkVersion=28.0.13004108
```

On Windows, use `python` and `.\gradlew.bat`.

Optional host audio regression tests, with a host C/C++ compiler and CMake
3.22 or later installed:

```bash
cmake -S tests -B build/native-tests -DCMAKE_BUILD_TYPE=Debug
cmake --build build/native-tests --parallel
ctest --test-dir build/native-tests --output-on-failure
```

To install a standalone module on a device connected through ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
```

For rooted use, enable Sing in Vector and scope it to Apple Music. For rootless
use, install the matching companion and patched Apple Music set using the
[patching guide](ROOTLESS_PATCHING.md). A successful build or benchmark does
not establish that the hooks work in a particular Apple Music version; test
login, playback, lyrics, controls, and performance on the intended device.

## 7. Publish a release manually

1. Update `versionName` and increment `versionCode` in
   [`app/build.gradle.kts`](../app/build.gradle.kts). ABI-specific version codes
   are derived automatically from that base code.
2. Run the checks, build with your release key, and test on a device. For a new
   Apple Music version, patch its original APK or split set again and test the
   resulting build before sharing it.
3. Commit the source and create a version tag, for example `git tag v1.3`.
   Push the commit and tag to your fork.
4. Open this fork's [Releases](https://github.com/xyz-sundram/sing-for-android/releases),
   choose **Draft a new release**, select the tag, write the changes and tested
   Apple Music/device versions, attach the chosen release APKs, and publish.
   For a patched set, include its companion, install instructions, configuration,
   and patch report together so users have matching files.

No GitHub Actions setup is needed. The existing in-app update checker and the
About screen's source link still point to the upstream repository; update
[`UpdateChecker.kt`](../app/src/main/java/org/francasio/sing/update/UpdateChecker.kt)
and [`InfoScreen.kt`](../app/src/main/java/org/francasio/sing/ui/InfoScreen.kt)
if you want those app links to use your own releases and source.

## Troubleshooting

| Problem | Check |
| --- | --- |
| SDK location not found | Set `ANDROID_HOME` or `sdk.dir` in `local.properties`. |
| NDK revision missing | Install `28.0.13004108` and pass `-PsingNdkVersion=28.0.13004108`. |
| Missing KissFFT or SpeexDSP files | Run `git submodule update --init --recursive`. |
| Java/Gradle compatibility error | Use JDK 17 or 21 for the Gradle wrapper; use 21 for LSPatch. Check Android Studio's Gradle JDK as well. |
| Rootless patcher cannot find build tools | Install Build Tools 35.0.0 and set `ANDROID_HOME` or pass `--sdk`. |
| MNN model unavailable | Build `libMNN.so` for the device's ABI, then rebuild Sing. |
| Update fails with a signature mismatch | Use the same keystore as the installed build. Switching keys requires a manual uninstall, which deletes that app's local data. |
