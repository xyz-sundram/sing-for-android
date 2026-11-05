#!/usr/bin/env python3
"""Interactively embed Sing in Apple Music with JingMatrix LSPatch (no root).

Python 3.10+, JDK 21+, Android SDK Build Tools 35+. See docs/ROOTLESS_PATCHING.md.
No third-party Python packages. Inputs are never overwritten; installation is manual.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import shutil
import struct
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.apple.android.music"
MODULE_PACKAGE = "org.francasio.sing"
CATALOG_URL = "https://huggingface.co/Francasio/sing-android-weights-db/resolve/main/models.json"
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
FEATURES = {
    "preserve_side": ("Preserve backing vocals and stereo side channel", False),
    "two_way_slider": ("Two-way vocal / instrumental slider", True),
    "allow_zero_floor": ("Allow full vocal attenuation", True),
    "force_song_eligibility": ("Enable Sing controls on all songs", True),
    "center_group_lyrics": ("Center grouped lyrics", False),
    "blur_unfocused_lyrics": ("Blur unfocused lyrics", False),
    "english_translation_fallback": ("English lyric translation fallback", True),
    "eco_mode": ("Eco mode", True),
    "apple_red_tint": ("Use Apple red accents in Apple Music", False),
    "liquid_glass": ("Refractive glass for Apple Music navigation and mini-player (Android 13+)", False),
}
DEFAULT_SETTINGS = {key: default for key, (_, default) in FEATURES.items()}
DEFAULT_SETTINGS.update(hardware_target="CPU", thread_count=4)
CONFIG_ASSET = "assets/sing_patch_defaults.json"
SUPPORT_ASSET = "assets/sing_patch_support.json"


class PatchError(Exception):
    pass


def input_path(value: str) -> Path:
    """Accept the supplied Windows path when invoked under WSL too."""
    value = value.strip().strip('"')
    if os.name != "nt" and re.match(r"^[A-Za-z]:[\\/]", value):
        value = f"/mnt/{value[0].lower()}/" + value[3:].replace("\\", "/")
    return Path(value).expanduser().resolve()


def manifest_attributes(data: bytes) -> dict:
    """Read root attributes from Android binary XML without SDK/Python dependencies."""
    strings = []
    if len(data) < 8 or struct.unpack_from("<H", data)[0] != 3:
        raise PatchError("APK manifest is not Android binary XML")

    def length(pos, utf8):
        if utf8:
            n = data[pos]
            return (((n & 127) << 8) | data[pos + 1], pos + 2) if n & 128 else (n, pos + 1)
        n = struct.unpack_from("<H", data, pos)[0]
        return (((n & 32767) << 16) | struct.unpack_from("<H", data, pos + 2)[0], pos + 4) if n & 32768 else (n, pos + 2)

    pos = struct.unpack_from("<H", data, 2)[0]
    while pos + 8 <= len(data):
        kind, header, size = struct.unpack_from("<HHI", data, pos)
        if size < header or header < 8 or pos + size > len(data):
            raise PatchError("Malformed binary XML chunk")
        if kind == 1:
            count, _, flags, start = struct.unpack_from("<IIII", data, pos + 8)
            utf8 = bool(flags & 256)
            for i in range(count):
                offset = pos + start + struct.unpack_from("<I", data, pos + header + i * 4)[0]
                if utf8:
                    _, offset = length(offset, True)
                n, offset = length(offset, utf8)
                strings.append(data[offset:offset + n * (1 if utf8 else 2)].decode("utf-8" if utf8 else "utf-16le"))
        elif kind == 0x102:
            name = struct.unpack_from("<I", data, pos + 20)[0]
            if strings[name] == "manifest":
                start, width, count = struct.unpack_from("<HHH", data, pos + 24)
                result = {}
                for i in range(count):
                    offset = pos + 16 + start + width * i
                    _, key, raw, _, _, value_type, value = struct.unpack_from("<IIIHBBI", data, offset)
                    result[strings[key]] = strings[raw] if raw != 0xFFFFFFFF else strings[value] if value_type == 3 else value
                return result
        pos += size
    raise PatchError("APK has no manifest element")


def apk_info(source) -> dict:
    with zipfile.ZipFile(source) as apk:
        attrs = manifest_attributes(apk.read("AndroidManifest.xml"))
        attrs["abis"] = sorted({n.split("/")[1] for n in apk.namelist() if n.startswith("lib/") and n.endswith(".so")})
        attrs["patched"] = "assets/lspatch/config.json" in apk.namelist()
        return attrs


def unpack_input(source: Path, destination: Path) -> list[Path]:
    destination.mkdir()
    if source.suffix.lower() == ".apk":
        target = destination / "base.apk"
        shutil.copyfile(source, target)
        return [target]
    if source.suffix.lower() not in (".apkm", ".apks", ".xapk"):
        raise PatchError("Expected .apk, .apkm, flat .apks, or .xapk input")
    paths = []
    with zipfile.ZipFile(source) as bundle:
        names = [n for n in bundle.namelist() if n.lower().endswith(".apk")]
        if not names or any(PurePosixPath(n).name != n or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*\.apk", n) for n in names):
            raise PatchError("Only flat split-APK archives are supported; export a device-specific set for bundletool archives")
        if len(names) != len({n.casefold() for n in names}):
            raise PatchError("Duplicate APK entries in archive")
        for name in names:
            target = destination / name
            with bundle.open(name) as src, target.open("wb") as dst:
                shutil.copyfileobj(src, dst)
            paths.append(target)
    return paths


def validate_apks(paths: list[Path]) -> tuple[Path, dict[Path, dict]]:
    infos = {path: apk_info(path) for path in paths}
    bases = [p for p, info in infos.items() if not info.get("split")]
    if len(bases) != 1:
        raise PatchError("Input must contain exactly one base APK")
    base = bases[0]
    split_ids = []
    for path, info in infos.items():
        if info.get("package") != PACKAGE:
            raise PatchError(f"{path.name}: expected {PACKAGE}, found {info.get('package')}")
        if info.get("versionCode") != infos[base].get("versionCode"):
            raise PatchError(f"{path.name}: split version does not match base")
        if info["patched"]:
            raise PatchError("Input is already LSPatch-patched; use the original Apple Music APKs")
        if info.get("split"):
            split_ids.append(info["split"])
    if len(split_ids) != len(set(split_ids)):
        raise PatchError("Input contains duplicate split identifiers")
    return base, infos


def choose(prompt: str, options: list[str], default=0) -> int:
    print(prompt)
    for i, label in enumerate(options, 1):
        print(f"  {i}. {label}")
    while True:
        answer = input(f"Choice [{default + 1}]: ").strip()
        if not answer:
            return default
        if answer.isdecimal() and 1 <= int(answer) <= len(options):
            return int(answer) - 1
        print("Enter one of the numbers above.")


def yes_no(prompt: str, default: bool) -> bool:
    while True:
        answer = input(f"{prompt} [{'Y/n' if default else 'y/N'}]: ").strip().lower()
        if answer in ("", "y", "yes", "n", "no"):
            return default if not answer else answer in ("y", "yes")


def version_tuple(value: str) -> tuple:
    numbers = tuple(int(n) for n in re.findall(r"\d+", value))
    return numbers + (0,) * max(0, 4 - len(numbers))


def catalog_models(catalog: dict, engine_version: str) -> list[dict]:
    if not isinstance(catalog, dict) or not isinstance(catalog.get("models"), list):
        raise PatchError("Catalog requires a models array")
    models = []
    for family in catalog.get("models", []):
        if not isinstance(family, dict) or not isinstance(family.get("versions"), list):
            raise PatchError("Each catalog model requires a versions array")
        if version_tuple(family.get("minVersion", family.get("minAppVersion", "0"))) > version_tuple(engine_version):
            continue
        for variant in family.get("versions", []):
            if not isinstance(variant, dict):
                raise PatchError("Invalid catalog model variant")
            if variant.get("engine", "onnx") not in ("native", "onnx", "mnn"):
                continue
            if family.get("id") == "apple_sing" and variant.get("id") != "apple_sing_native_fp32":
                continue  # Same retired Vi-NNet filter as ModelLoader.
            if version_tuple(variant.get("minVersion", variant.get("minAppVersion", "0"))) > version_tuple(engine_version):
                continue
            ident, filename = variant.get("id", ""), variant.get("fileName", "")
            if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", ident) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*\.(onnx|mnn|vinet)", filename):
                raise PatchError("Catalog contains an invalid model identifier or filename")
            models.append({**variant, "label": f"{family['displayName']} / {variant['displayName']} ({variant.get('fileSizeDesc', 'size unknown')})"})
    if not models or len({m["id"] for m in models}) != len(models):
        raise PatchError("Catalog has no compatible models, or has duplicate model IDs")
    return models


def validate_settings(settings: dict, models: list[dict]) -> dict:
    if not isinstance(settings, dict):
        raise PatchError("settings must be an object")
    unknown = set(settings) - set(DEFAULT_SETTINGS) - {"selected_model"}
    if unknown:
        raise PatchError(f"Unknown settings: {', '.join(sorted(unknown))}")
    result = {**DEFAULT_SETTINGS, **settings}
    for key in FEATURES:
        if type(result[key]) is not bool:
            raise PatchError(f"{key} must be true or false")
    if result["hardware_target"] not in ("CPU", "GPU", "NPU", "NNAPI"):
        raise PatchError("hardware_target must be CPU, GPU, NPU, or NNAPI")
    if type(result["thread_count"]) is not int or not 1 <= result["thread_count"] <= 12:
        raise PatchError("thread_count must be between 1 and 12")
    model = next((m for m in models if m["id"] == result.get("selected_model")), None)
    if not model:
        raise PatchError("selected_model must be an exact compatible catalog variant ID")
    engine, target = model.get("engine", "onnx"), result["hardware_target"]
    if engine == "native" and target != "CPU":
        raise PatchError("Native Vi-NNet models support CPU only")
    if engine == "onnx" and target not in ("CPU", "NPU"):
        raise PatchError("ONNX models support CPU or Qualcomm NPU; choose MNN for GPU/NNAPI")
    return result


def configure(models: list[dict], supplied: dict | None, interactive: bool) -> dict:
    if supplied is not None:
        if not isinstance(supplied, dict) or supplied.get("schema_version") != 1:
            raise PatchError("Configuration requires schema_version: 1")
        return validate_settings(supplied.get("settings"), models)
    if not interactive:
        raise PatchError("--non-interactive requires --config")
    model = models[choose("Select the separation model:", [m["label"] for m in models])]
    targets = ["CPU"] if model.get("engine") == "native" else ["CPU", "NPU"] if model.get("engine", "onnx") == "onnx" else ["CPU", "GPU", "NNAPI", "NPU"]
    target = targets[choose("Compute target (NPU requires compatible Qualcomm hardware):", targets)]
    while True:
        value = input("CPU worker threads, 1–12 [4]: ").strip() or "4"
        if value.isdecimal() and 1 <= int(value) <= 12:
            break
    settings = {"selected_model": model["id"], "hardware_target": target, "thread_count": int(value)}
    for key, (label, default) in FEATURES.items():
        settings[key] = yes_no(label, default)
    return validate_settings(settings, models)


def run(command: list, *, capture=False, cwd=None) -> str:
    command = [str(arg) for arg in command]
    result = subprocess.run(command, cwd=cwd, text=True, stdout=subprocess.PIPE if capture else None, stderr=subprocess.STDOUT if capture else None)
    if result.returncode:
        raise PatchError(f"{Path(command[0]).name} failed (exit {result.returncode})" + (f":\n{result.stdout}" if capture else "; see output above"))
    return result.stdout or ""


def find_build_tools(sdk: Path | None) -> tuple[Path, Path]:
    roots = [sdk] if sdk else [input_path(os.environ[k]) for k in ("ANDROID_HOME", "ANDROID_SDK_ROOT") if os.environ.get(k)]
    roots += [Path.home() / "Android/Sdk", Path.home() / "AppData/Local/Android/Sdk"]
    for root in roots:
        directory = root / "build-tools"
        if not directory.is_dir():
            continue
        for build in sorted(directory.iterdir(), key=lambda p: version_tuple(p.name), reverse=True):
            align = build / ("zipalign.exe" if os.name == "nt" else "zipalign")
            signer = build / "lib/apksigner.jar"
            if version_tuple(build.name) >= version_tuple("35") and align.is_file() and signer.is_file():
                return align, signer
    raise PatchError("Android SDK Build Tools 35+ not found. Set ANDROID_HOME or pass --sdk (use the SDK for your host OS).")


def module_support(path: Path, abi: str) -> dict:
    with zipfile.ZipFile(path) as module:
        if SUPPORT_ASSET not in module.namelist():
            raise PatchError("Sing APK lacks patch-default support. Build this checkout first, or omit --module to build it.")
        if json.loads(module.read(SUPPORT_ASSET)).get("schema_version") != 1:
            raise PatchError("Unsupported Sing patch-default schema")
        if f"lib/{abi}/libsing_dsp.so" not in module.namelist():
            raise PatchError(f"Sing module has no native engine for {abi}")
        if "META-INF/xposed/java_init.list" not in module.namelist():
            raise PatchError("Sing APK is not a modern Xposed module")
    info = apk_info(path)
    if info.get("package") != MODULE_PACKAGE:
        raise PatchError("--module must be the Sing for Android APK")
    return info


def add_defaults(module: Path, output: Path, config: dict, catalog: bytes):
    replacements = {CONFIG_ASSET: json.dumps(config, indent=2).encode(), "assets/models.json": catalog}
    with zipfile.ZipFile(module) as source, zipfile.ZipFile(output, "w") as dest:
        if len(source.namelist()) != len(set(source.namelist())):
            raise PatchError("Module APK has duplicate ZIP entries")
        for entry in source.infolist():
            name = entry.filename
            if name in replacements or re.fullmatch(r"META-INF/[^/]+\.(SF|RSA|DSA|EC|MF)", name, re.IGNORECASE):
                continue
            # Preserve compression, especially STORED native libraries, and stream large entries.
            with source.open(entry) as src, dest.open(entry, "w") as dst:
                shutil.copyfileobj(src, dst)
        for name, data in replacements.items():
            dest.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)


def align_patched_apk(source: Path, output: Path):
    """Keep LSPatch's nested original page aligned as well as its native libraries.

    zipalign only page-aligns .so files. Repacking also resolves LSPatch's linked
    ZIP entries into ordinary entries, while preserving the original APK bytes.
    """
    with zipfile.ZipFile(source) as src, zipfile.ZipFile(output, "w") as dest:
        for entry in src.infolist():
            if entry.filename == "assets/lspatch/origin.apk":
                if entry.compress_type != zipfile.ZIP_STORED:
                    raise PatchError("LSPatch's nested original must be uncompressed")
                # LSPatch intentionally links other entries into this nested ZIP.
                # Python's general ZIP-bomb guard otherwise rejects that overlap.
                entry._end_offset = None
            alignment = 16384 if entry.filename.endswith(".so") else 4096 if entry.filename == "assets/lspatch/origin.apk" else 4
            entry.extra = b""
            if entry.compress_type == zipfile.ZIP_STORED:
                position = dest.fp.tell() + 30 + len(entry.filename.encode("utf-8"))
                padding = (-position) % alignment
                if 0 < padding < 4:
                    padding += alignment
                if padding:
                    entry.extra = struct.pack("<HH", 0xFFFF, padding - 4) + bytes(padding - 4)
            with src.open(entry) as input_file, dest.open(entry, "w") as output_file:
                shutil.copyfileobj(input_file, output_file)


def sign_apk(source: Path, output: Path, work: Path, tools: tuple, java: str, keystore: Path):
    align, signer = tools
    aligned = work / f"{output.stem}.aligned.apk"
    with zipfile.ZipFile(source) as z:
        nested = "assets/lspatch/origin.apk" in z.namelist()
    if nested:
        align_patched_apk(source, aligned)
    else:
        run([align, "-P", "16", "-f", "4", source, aligned], capture=True)
    run([java, "-jar", signer, "sign", "--ks", keystore, "--ks-key-alias", "sing",
         "--ks-pass", "pass:android", "--key-pass", "pass:android", "--alignment-preserved", "true",
         "--v4-signing-enabled", "false", "--out", output, aligned], capture=True)
    run([java, "-jar", signer, "verify", "--verbose", output], capture=True)
    run([align, "-c", "-P", "16", "4", output], capture=True)
    if nested:
        with zipfile.ZipFile(output) as z, output.open("rb") as stream:
            entry = z.getinfo("assets/lspatch/origin.apk")
            stream.seek(entry.header_offset + 26)
            name_size, extra_size = struct.unpack("<HH", stream.read(4))
            if (entry.header_offset + 30 + name_size + extra_size) % 4096:
                raise PatchError("Nested original APK lost its page alignment")


def ensure_keystore(path: Path, keytool: str):
    if path.is_file():
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    run([keytool, "-genkeypair", "-keystore", path, "-storetype", "JKS", "-storepass", "android", "-keypass", "android",
         "-alias", "sing", "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000", "-dname", "CN=Sing local patch", "-noprompt"], capture=True)
    path.chmod(0o600)


def sha256(path: Path) -> str:
    with path.open("rb") as src:
        digest = hashlib.sha256()
        for chunk in iter(lambda: src.read(1024 * 1024), b""):
            digest.update(chunk)
        return digest.hexdigest()


def write_installers(directory: Path, apks: list[str]):
    # Relative paths work after moving the entire output directory.
    unix = " ".join(shlex.quote(name) for name in apks)
    windows = " ".join(f'"{name}"' for name in apks)
    (directory / "install.sh").write_bytes(('#!/bin/sh\nset -eu\ncd "$(dirname "$0")"\nadb install -r sing-configured.apk\nadb install-multiple -r ' + unix + '\n').encode("utf-8"))
    (directory / "install.sh").chmod(0o755)
    (directory / "install.bat").write_bytes(('@echo off\r\ncd /d "%~dp0"\r\nadb install -r sing-configured.apk\r\nif errorlevel 1 exit /b 1\r\nadb install-multiple -r ' + windows + '\r\n').encode("utf-8"))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", nargs="?", type=input_path, help="Apple Music APK or split archive; Windows paths also work in WSL")
    parser.add_argument("--inspect", action="store_true", help="Inspect the input only; needs no SDK, downloads, or output directory")
    parser.add_argument("--lspatch", type=input_path, help="JingMatrix LSPatch v1.2+ CLI jar (libxposed API 102)")
    parser.add_argument("--module", type=input_path, help="Sing APK built from this checkout; otherwise build :app:assembleRelease")
    parser.add_argument("--ndk-version", help="Override Sing's NDK version when building the module")
    parser.add_argument("--sdk", type=input_path)
    parser.add_argument("--java", default="java", help="JDK 21+ java executable")
    parser.add_argument("--keytool", default="keytool")
    parser.add_argument("--keystore", type=input_path, default=ROOT / ".patcher/signing.jks", help="Local signing identity (alias sing, password android); keep for future updates")
    parser.add_argument("--catalog", type=input_path, help="Local models.json; otherwise fetch the project's model catalog")
    parser.add_argument("--config", type=input_path, help="JSON configuration instead of interactive model/feature questions")
    parser.add_argument("--non-interactive", action="store_true")
    parser.add_argument("--abi", choices=ABIS)
    parser.add_argument("--output", type=input_path, default=ROOT / "artifacts/apple-music-sing")
    args = parser.parse_args(argv)
    if not args.input:
        if args.non_interactive or not sys.stdin.isatty():
            raise PatchError("Provide an input APK/APKM path")
        args.input = input_path(input("Apple Music APK/APKM path: "))
    if not args.input.is_file():
        raise PatchError(f"Input does not exist: {args.input}")
    with tempfile.TemporaryDirectory(prefix="sing-patch-") as temporary:
        work = Path(temporary)
        paths = unpack_input(args.input, work / "input")
        base, infos = validate_apks(paths)
        available = sorted({abi for info in infos.values() for abi in info["abis"]})
        print(f"Apple Music {infos[base].get('versionName', '?')} (versionCode {infos[base].get('versionCode')}); {len(paths)} APK(s); ABIs: {', '.join(available) or 'no native libraries'}")
        if args.inspect:
            print(json.dumps({p.name: info for p, info in infos.items()}, indent=2))
            return 0
        if not args.lspatch or not args.lspatch.is_file():
            raise PatchError("Pass --lspatch /path/to/JingMatrix/lspatch.jar; see docs/ROOTLESS_PATCHING.md")
        if args.output.exists():
            raise PatchError(f"Output already exists: {args.output}. Choose a new --output directory.")
        compatible = [abi for abi in ABIS if abi in available]
        if not compatible:
            raise PatchError("Input has no native ABI supported by Sing (x86 is unsupported)")
        interactive = not args.non_interactive and sys.stdin.isatty()
        abi = args.abi
        if not abi:
            if not interactive:
                raise PatchError("Pass --abi for non-interactive runs")
            abi = compatible[choose("Select your Android device's ABI:", compatible)]
        if abi not in compatible:
            raise PatchError(f"Input has no native libraries for {abi}")
        tools = find_build_tools(args.sdk)
        java_version = run([args.java, "-version"], capture=True)
        match = re.search(r'version "(\d+)', java_version)
        if not match or int(match[1]) < 21:
            raise PatchError("LSPatch v1.2 requires JDK 21+. Set JAVA_HOME/PATH or --java.")
        patch_help = run([args.java, "-jar", args.lspatch, "--help"], capture=True)
        if "--embed" not in patch_help or "--sigbypasslv" not in patch_help:
            raise PatchError("LSPatch jar lacks required CLI options")
        with zipfile.ZipFile(args.lspatch) as jar:
            loader = jar.read("assets/lspatch/loader.dex")
            if b"Lio/github/libxposed/api/XposedModuleInterface$PackageReadyParam;" not in loader:
                raise PatchError("LSPatch runtime lacks modern libxposed support; use JingMatrix v1.2+")
        module = args.module
        if not module:
            if not (ROOT / "extern/kissfft/CMakeLists.txt").is_file() or not (ROOT / "extern/speexdsp/libspeexdsp/resample.c").is_file():
                raise PatchError("Initialize native sources first: git submodule update --init --recursive")
            wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
            build = [wrapper, ":app:assembleRelease"]
            if args.ndk_version:
                build.append(f"-PsingNdkVersion={args.ndk_version}")
            run(build, cwd=ROOT)
            module = ROOT / "app/build/outputs/apk/release/app-universal-release.apk"
        module_info = module_support(module, abi)
        if args.catalog:
            catalog = args.catalog.read_bytes()
        else:
            print("Fetching Sing model catalog...")
            with urllib.request.urlopen(CATALOG_URL, timeout=60) as response:
                catalog = response.read(4 * 1024 * 1024 + 1)
            if len(catalog) > 4 * 1024 * 1024:
                raise PatchError("Model catalog is too large")
        models = catalog_models(json.loads(catalog), str(module_info.get("versionName", "0")))
        # A model requiring MNN must have a matching native runtime in the chosen APK.
        with zipfile.ZipFile(module) as z:
            if f"lib/{abi}/libMNN.so" not in z.namelist():
                models = [m for m in models if m.get("engine") != "mnn"]
        if not models:
            raise PatchError("The catalog has no models supported by this module's native runtimes")
        supplied = json.loads(args.config.read_text(encoding="utf-8")) if args.config else None
        settings = configure(models, supplied, interactive)
        if settings["hardware_target"] == "NPU" and abi != "arm64-v8a":
            raise PatchError("Qualcomm NPU acceleration requires arm64-v8a; choose CPU for this ABI")
        config = {"schema_version": 1, "settings": settings}
        print(json.dumps(config, indent=2))
        ensure_keystore(args.keystore, args.keytool)
        unsigned = work / "module-unsigned.apk"
        add_defaults(module, unsigned, config, catalog)
        staged = work / "output"
        staged.mkdir()
        configured = staged / "sing-configured.apk"
        sign_apk(unsigned, configured, work, tools, args.java, args.keystore)
        patch_output = work / "patched"
        # LSPatch patches the base only; untouched splits are aligned and re-signed below.
        print("Embedding Sing with LSPatch...", flush=True)
        run([args.java, "-jar", args.lspatch, "--sigbypasslv", "2", "--embed", configured, "--output", patch_output, base], capture=True)
        patched = list(patch_output.glob("*.apk"))
        if len(patched) != 1 or not apk_info(patched[0])["patched"]:
            raise PatchError("LSPatch did not produce a patched base APK")
        with zipfile.ZipFile(patched[0]) as z:
            embedded = f"assets/lspatch/modules/{MODULE_PACKAGE}.apk"
            if embedded not in z.namelist() or hashlib.sha256(z.read(embedded)).hexdigest() != sha256(configured):
                raise PatchError("LSPatch output does not embed the configured Sing module")
        final_apks = []
        for path in [base] + [p for p in paths if p != base]:
            split_abis = set(infos[path]["abis"])
            if path != base and split_abis and abi not in split_abis:
                continue
            output = staged / ("base.apk" if path == base else path.name)
            if output.name == "sing-configured.apk":
                raise PatchError("Split filename conflicts with output module")
            sign_apk(patched[0] if path == base else path, output, work, tools, args.java, args.keystore)
            final_apks.append(output.name)
        # A single-APK input may contain other ABIs; the device still selects its own ABI.
        write_installers(staged, final_apks)
        (staged / "patch-config.json").write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
        report = {"input": str(args.input), "input_sha256": sha256(args.input), "apple_music": infos[base], "abi": abi,
                  "keystore": str(args.keystore), "module_sha256": sha256(module), "lspatch_sha256": sha256(args.lspatch),
                  "files": {p.name: sha256(p) for p in staged.glob("*.apk")}}
        (staged / "patch-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        with zipfile.ZipFile(staged / "apple-music-sing.apks", "w", compression=zipfile.ZIP_STORED) as bundle:
            for name in final_apks:
                bundle.write(staged / name, name)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(staged, args.output)
        print(f"\nPatched and verified: {args.output}\nInstall sing-configured.apk and all Apple Music splits using install.bat / install.sh.\nThe official Apple Music signature differs: an existing official install must be removed manually first (removes its local data/downloads).\nOpen Sing to download the selected model/runtime and benchmark your device, then open Apple Music.\nKeep your signing keystore for future patched updates. See docs/ROOTLESS_PATCHING.md.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (PatchError, OSError, ValueError, KeyError, struct.error, IndexError, zipfile.BadZipFile) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        sys.exit(1)
    except (EOFError, KeyboardInterrupt):
        print("\nCancelled.", file=sys.stderr)
        sys.exit(130)
