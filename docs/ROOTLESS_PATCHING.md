# Patch Apple Music without root

`scripts/patch_apple_music.py` embeds this project's Sing engine with
[JingMatrix LSPatch](https://github.com/JingMatrix/LSPatch). It asks which model
variant, compute target, thread count, and optional features you want. It produces
signed Apple Music APKs and a configured Sing companion APK. Neither root nor an
Xposed manager is needed on the phone.

The features are enabled by the existing Sing hooks; the patcher does not change
your Apple Music subscription or grant access to music. Hook compatibility and
real-time inference performance still depend on the Apple Music version and device.

Start with the [local build guide](BUILDING.md) for SDK setup, module compilation,
optional MNN support, and manual release signing. Updates and discussion:
[Sundram's Projects on Telegram](https://t.me/sundramsprojeckts).

## Prerequisites

- Python 3.10 or later, JDK **21 or later** (`java` and `keytool` on PATH).
  LSPatch v1.2 needs Java 21 even though Sing's build accepts Java 17.
- Android SDK **Build Tools 35+** for your host OS. Set `ANDROID_HOME` or use `--sdk`.
- The release CLI `.jar` from
  [JingMatrix LSPatch v1.2+](https://github.com/JingMatrix/LSPatch/releases).
  Older LSPosed LSPatch builds do not support this module's libxposed API 102.
  The patcher never downloads or runs a JAR automatically.
- A Sing module built **from this checkout**, including patch-default support.
  Omit `--module` to run `:app:assembleRelease`; this requires the normal project
  build prerequisites (SDK 34, configured NDK, CMake, native submodules).
  Initialize submodules with `git submodule update --init --recursive`.
  Pass `--ndk-version 28.0.13004108` to build with the installed NDK revision
  (or `-PsingNdkVersion=28.0.13004108` when building with Gradle directly).
  MNN models appear only if the module includes `libMNN.so` for the selected ABI;
  build MNN with `scripts/build_mnn.sh` before building Sing to include them.
- Original Apple Music `.apk`, `.apkm`, `.xapk`, or **flat** `.apks` archive.
  APKMirror APKMs are supported directly. Nested bundletool archives need to be
  exported as a flat device-specific APK set first.

## Run on Windows

From the repository directory in PowerShell:

```powershell
python scripts/patch_apple_music.py `
  'C:\Downloads\apple-music.apkm' `
  --lspatch 'C:\Tools\lspatch-v1.2-487-release.jar' `
  --ndk-version 28.0.13004108
```

With an already built module, add:

```powershell
  --module 'app\build\outputs\apk\release\app-universal-release.apk'
```

Choose the **phone's** architecture (usually `arm64-v8a`), then select the model
and features. Each model menu entry represents a specific runtime and precision.
ONNX supports CPU/Qualcomm NPU; MNN supports CPU/GPU/NNAPI/NPU; native Vi-NNet
uses CPU. NPU requires compatible Qualcomm hardware and arm64. Other devices
should start with CPU. Benchmark the selected configuration on the phone.

## Run on Linux / WSL

```bash
python3 scripts/patch_apple_music.py \
  /path/to/apple-music.apkm \
  --lspatch /path/to/lspatch.jar \
  --ndk-version 28.0.13004108
```

Windows input paths are also translated to `/mnt/<drive>/...` under WSL. Use a
**Linux** JDK/SDK when running Linux Python. `--java` and `--keytool` can select
specific JDK executables; Gradle uses `JAVA_HOME`/PATH as usual.

To patch a newer Apple Music release manually, obtain its original APK or split
archive and rerun the same command with that input and a new `--output` directory.
Keep the same `.patcher/signing.jks` so the resulting files can update your
previous patched installation. New app versions can change the hook targets;
test login, playback, lyrics, and Sing controls on the device after each update.

To inspect the sample without building, signing, downloading, or installing:

```bash
python3 scripts/patch_apple_music.py /path/to/apple-music.apkm --inspect
```

For repeatable runs, edit [patch-config.example.json](../scripts/patch-config.example.json):

```bash
python3 scripts/patch_apple_music.py /path/to/apple-music.apkm \
  --lspatch /path/to/lspatch.jar \
  --module app/build/outputs/apk/release/app-universal-release.apk \
  --abi arm64-v8a --config scripts/patch-config.example.json --non-interactive \
  --output artifacts/my-patched-music
```

The script fetches the same model catalog used by Sing. Use `--catalog /path/to/models.json`
for offline runs. Configurations use exact variant IDs from that catalog. Models
requiring a newer Sing version and the removed LiteRT runtime are filtered out.

The interactive script also offers Apple red tint and Liquid Glass. These are
editable later under **Apple Music Appearance** in Sing. See
[Appearance implementation and validation](APPEARANCE.md) for platform support
and rendering limits.

## Output and installation

The default output directory is `artifacts/apple-music-sing/`. Existing output
directories and all original input files are left intact; choose a new `--output`
for another run. It contains:

- `base.apk` plus resource/language splits and the selected ABI's native split.
- `sing-configured.apk`: companion settings, model downloads, and benchmark UI;
  the identical APK is embedded into the patched base.
- `apple-music-sing.apks`: flat ZIP of the patched Apple Music APK set.
- `install.bat` / `install.sh`: install the companion and **all** necessary splits
  with ADB. Installation is never run automatically by the patcher.
- `patch-config.json` and `patch-report.json`: chosen settings, versions, and SHA-256
  hashes of inputs, tools, module, and output APKs.

The base uses LSPatch embedded mode and signature bypass level 2. The companion
and every Apple Music split are aligned, signed with the same local key, and
signature-verified with Android's tools. Resource splits are retained without
merging or decompiling Apple Music's code.

1. If the official Apple Music app is installed, remove it manually before
   installing the patched build: its signing certificate differs. **Uninstalling
   deletes local settings and downloaded tracks.** The scripts never uninstall
   anything. A shared-UID conflict with another Apple app may also prevent installation.
   An existing Sing companion signed with a different key also needs manual
   removal before installing `sing-configured.apk`; this deletes its settings and models.
2. Enable USB debugging and run `install.bat` on Windows or `sh install.sh` on
   Linux with exactly the intended device connected. Or use a split APK installer
   for the `.apks` archive and install `sing-configured.apk` separately.
3. Open **Sing for Android**. Download the preselected model and any required
   runtime, run its benchmark, then open Apple Music and play a song with lyrics.
   Model weights and on-demand native runtimes are **not bundled** by the script;
   their initial download requires internet access.

Selections are first-run defaults in both apps, and remain editable in Sing.
Existing saved settings take priority. For a previous installation, change the
settings in Sing or clear the app's data if you want to apply defaults again.
Feature choices configure the existing hooks; they do not remove unused classes.

## When the Sing control is missing

The status card checks the installed Apple Music APK and its connection to Sing.
Installing the companion alone does not establish that the engine is running in
Apple Music. A successful companion benchmark also does not prove that connection.

| Status | Meaning / next step |
| --- | --- |
| Apple Music not installed | Install the patched APK set in the same Android profile as Sing. |
| Sing patch not detected | The installed Apple Music APK has no embedded patch. Install the generated set for rootless use. |
| Sing missing from Apple Music patch | A loader is present without the embedded Sing APK. Repatch in embedded mode. |
| Waiting for Apple Music | The setup is present, but the host has not contacted Sing. Tap **Music**, open a song, then return to Sing. |
| Connected to Apple Music | A recent heartbeat from the installed Apple Music was received. This confirms contact, not successful audio separation. |
| Previously connected | A heartbeat was received earlier, but it is older than two minutes. This does not establish a failure; reopen Apple Music to check. |
| Sing versions do not match | Install matching Sing and patched Apple Music versions, then restart Apple Music. |
| Unable to check Apple Music | The installation could not be inspected. Open Apple Music and return to Sing to check for contact. |

The installation check runs when Sing resumes; the connection status refreshes
every five seconds while Sing is visible. A bound Xposed service in the companion
alone never counts as a confirmed connection to Apple Music.

For Pixel 6a, use **UVR MDX-NET 1-9703 → ONNX FP32**, **CPU**, and **4 threads**.
Download that model until its status reads **Downloaded**, wait for the ONNX
runtime download, and use **Run Speed Benchmark**. Turn on **Enable for All Songs**.
Then force-stop Apple Music from Android settings, open it with the **Music**
button in Sing, start a song with lyrics, and open the full lyrics screen. Return
to Sing to read the connection status.

If the status stays **Waiting for Apple Music** after that, collect startup logs
instead of installing an Xposed manager or repeatedly downloading models. With
USB debugging enabled and ADB on PATH, start capture on your computer:

```bash
adb logcat -v threadtime AppleMusicSing:V AppleSing_Settings:V AppleSing_Processor:V AppleSing_ModelLoader:V AppleSing_OnnxFactory:V LSPatch:V AndroidRuntime:E '*:S' > sing-startup.log
```

For Windows Command Prompt, use `"*:S"` instead of `'*:S'`. While capture is
running, force-stop and reopen Apple Music on the phone, play a song, and open
lyrics. Press **Ctrl+C** on the computer. These filtered logs help distinguish
loader errors, provider access failures, and model initialization errors.

An updated companion signed with the same local key can be installed over the
previous companion (`adb install -r sing-configured.apk`) to preserve its settings
and models. A companion-only status UI update does not require repatching the
already installed Apple Music; future full patches include the updated UI too.

### Vocals jump back to full volume or playback pauses

The streaming mixer waits for separated audio during inference underruns. Older
builds filled missing processed frames with the original recording, which could
restore full vocals regardless of the slider position. Current builds retain
those frames until inference finishes; slow inference can cause buffering rather
than leaking the original vocals. Slider changes update the mixer directly, and
gain smoothing follows emitted audio, including output drained between input calls.

MDX models require about 5.92 seconds of decoded audio for their first window,
plus inference time. Activation can therefore briefly buffer even when the
steady-state benchmark is fast enough. A result below **1× Real-Time** means the
selected configuration cannot sustain playback on that device. Choose another
available model and keep it only if its on-device benchmark exceeds 1× with some
headroom. A model change in Sing does not require repatching Apple Music.

With **Two-Way Slider Mode** enabled, the lower half controls vocal gain and the
upper half removes the instrumental: 0% is instrumental, 50% is the original mix,
and 100% is vocals only. Turn it off for the usual full-range vocal-only slider.

Keep `.patcher/signing.jks` (or your `--keystore` path) to install future patched
updates without changing the local signing identity. The generated local key
uses alias `sing` and password `android`; it is for personal local builds, not
public release signing. It is excluded from Git along with generated APKs.

## Checks

```bash
python3 -m unittest discover -s scripts/tests -v
./gradlew :app:testDebugUnitTest --tests org.francasio.sing.PatchDefaultsTest
```

Successful APK patching and signature verification do not prove that audio hooks,
login, playback, or the selected hardware accelerator work on a device. Test these
on Android 11+ with a valid Apple Music account before relying on the patched app.
