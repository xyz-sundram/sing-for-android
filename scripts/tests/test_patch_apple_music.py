import importlib.util
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("patcher", Path(__file__).resolve().parents[1] / "patch_apple_music.py")
patcher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(patcher)


def binary_manifest(package=patcher.PACKAGE, split="", version=1614, utf8=True):
    values = ["manifest", "package", package, "versionCode", "split", split, "versionName", "7.0.0"]
    strings, offsets = b"", []
    for value in values:
        offsets.append(len(strings))
        encoded = value.encode("utf-8" if utf8 else "utf-16le")
        strings += bytes([len(value), len(encoded)]) + encoded + b"\x00" if utf8 else struct.pack("<H", len(value)) + encoded + b"\x00\x00"
    pool_size = 28 + 4 * len(values) + len(strings)
    pool = struct.pack("<HHIIIIII", 1, 28, pool_size, len(values), 0, 256 if utf8 else 0, 28 + 4 * len(values), 0)
    pool += struct.pack("<" + "I" * len(offsets), *offsets) + strings
    attrs = [(1, 2, 3, 2), (3, 0xFFFFFFFF, 16, version), (4, 5, 3, 5), (6, 7, 3, 7)]
    node = struct.pack("<HHIII", 0x102, 16, 36 + len(attrs) * 20, 1, 0xFFFFFFFF)
    node += struct.pack("<IIHHHHHH", 0xFFFFFFFF, 0, 20, 20, len(attrs), 0, 0, 0)
    for key, raw, value_type, value in attrs:
        node += struct.pack("<IIIHBBI", 0xFFFFFFFF, key, raw, 8, 0, value_type, value)
    return struct.pack("<HHI", 3, 8, 8 + len(pool) + len(node)) + pool + node


def make_apk(path, **kwargs):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("AndroidManifest.xml", binary_manifest(**kwargs))


class PatcherTest(unittest.TestCase):
    def test_binary_manifests_and_typed_attributes(self):
        for utf8 in (True, False):
            attrs = patcher.manifest_attributes(binary_manifest(split="config.arm64_v8a", utf8=utf8))
            self.assertEqual(attrs["package"], patcher.PACKAGE)
            self.assertEqual(attrs["versionCode"], 1614)
            self.assertEqual(attrs["split"], "config.arm64_v8a")

    def test_rejects_mixed_packages_versions_and_duplicate_splits(self):
        with tempfile.TemporaryDirectory() as d:
            base, a, b = [Path(d) / name for name in ("base.apk", "a.apk", "b.apk")]
            make_apk(base)
            for kwargs, message in [({"package": "other", "split": "a"}, "expected"), ({"version": 1, "split": "a"}, "version")]:
                make_apk(a, **kwargs)
                with self.assertRaisesRegex(patcher.PatchError, message):
                    patcher.validate_apks([base, a])
            make_apk(a, split="config.arm64_v8a")
            make_apk(b, split="config.arm64_v8a")
            with self.assertRaisesRegex(patcher.PatchError, "duplicate split"):
                patcher.validate_apks([base, a, b])

    def test_rejects_traversal_and_nested_bundletool_archives(self):
        with tempfile.TemporaryDirectory() as d:
            for i, name in enumerate(("../base.apk", "splits/base.apk", "..\\base.apk")):
                archive = Path(d) / f"{i}.apkm"
                with zipfile.ZipFile(archive, "w") as z:
                    z.writestr(name, b"apk")
                with self.assertRaisesRegex(patcher.PatchError, "flat"):
                    patcher.unpack_input(archive, Path(d) / f"input{i}")

    def test_add_defaults_preserves_native_bytes_and_compression(self):
        with tempfile.TemporaryDirectory() as d:
            source, dest = Path(d) / "module.apk", Path(d) / "result.apk"
            native = b"\x7fELF" + bytes(range(256)) * 30
            with zipfile.ZipFile(source, "w") as z:
                z.writestr("lib/arm64-v8a/libsing_dsp.so", native, compress_type=zipfile.ZIP_STORED)
                z.writestr("META-INF/SIGN.RSA", b"old signature")
                z.writestr("META-INF/xposed/java_init.list", b"org.francasio.sing.SingXposedEntry")
                z.writestr(patcher.CONFIG_ASSET, b"old config")
            config = {"schema_version": 1, "settings": {"selected_model": "test"}}
            patcher.add_defaults(source, dest, config, b'{"models":[]}')
            with zipfile.ZipFile(dest) as z:
                self.assertEqual(z.read("lib/arm64-v8a/libsing_dsp.so"), native)
                self.assertEqual(z.getinfo("lib/arm64-v8a/libsing_dsp.so").compress_type, zipfile.ZIP_STORED)
                self.assertNotIn("META-INF/SIGN.RSA", z.namelist())
                self.assertIn("META-INF/xposed/java_init.list", z.namelist())
                self.assertEqual(json.loads(z.read(patcher.CONFIG_ASSET)), config)
                self.assertEqual(z.namelist().count(patcher.CONFIG_ASSET), 1)

    def test_patched_apk_preserves_nested_original_and_native_alignment(self):
        with tempfile.TemporaryDirectory() as d:
            source, dest = Path(d) / "patched.apk", Path(d) / "aligned.apk"
            original = b"original apk bytes" * 300
            native = b"native library bytes" * 300
            with zipfile.ZipFile(source, "w") as z:
                z.writestr("assets/lspatch/origin.apk", original, compress_type=zipfile.ZIP_STORED)
                z.writestr("assets/lspatch/so/arm64-v8a/liblspatch.so", native, compress_type=zipfile.ZIP_STORED)
                z.writestr("AndroidManifest.xml", binary_manifest())
            patcher.align_patched_apk(source, dest)
            with zipfile.ZipFile(dest) as z, dest.open("rb") as stream:
                for name, data, alignment in [("assets/lspatch/origin.apk", original, 4096), ("assets/lspatch/so/arm64-v8a/liblspatch.so", native, 16384)]:
                    self.assertEqual(z.read(name), data)
                    entry = z.getinfo(name)
                    stream.seek(entry.header_offset + 26)
                    name_size, extra_size = struct.unpack("<HH", stream.read(4))
                    self.assertEqual((entry.header_offset + 30 + name_size + extra_size) % alignment, 0)

    def test_model_catalog_filters_removed_and_newer_engines(self):
        catalog = {"models": [{"displayName": "Example", "versions": [
            {"id": "cpu", "displayName": "CPU", "engine": "onnx", "fileName": "test.onnx"},
            {"id": "removed", "displayName": "LiteRT", "engine": "litert", "fileName": "test.tflite"},
            {"id": "future", "displayName": "Future", "engine": "onnx", "fileName": "test.onnx", "minVersion": "9.0"},
        ]}]}
        self.assertEqual([m["id"] for m in patcher.catalog_models(catalog, "1.2")], ["cpu"])
        catalog["models"][0]["minVersion"] = "1.2.0"
        self.assertEqual([m["id"] for m in patcher.catalog_models(catalog, "1.2")], ["cpu"])

    def test_rejects_invalid_config_and_unsupported_compute_targets(self):
        models = [{"id": "onnx", "engine": "onnx"}, {"id": "native", "engine": "native"}]
        for settings in ({"thread_count": True}, {"thread_count": 0}, {"preserve_side": "false"}, {"selected_model": "missing"},
                         {"hardware_target": "GPU"}, {"selected_model": "native", "hardware_target": "NPU"}, {"bogus": True}):
            with self.assertRaises(patcher.PatchError):
                patcher.validate_settings({"selected_model": "onnx", **settings}, models)
        self.assertEqual(patcher.validate_settings({"selected_model": "onnx"}, models)["hardware_target"], "CPU")

    def test_noninteractive_requires_explicit_config(self):
        with self.assertRaisesRegex(patcher.PatchError, "requires --config"):
            patcher.configure([], None, False)

    def test_interactive_model_and_feature_choices_become_settings(self):
        models = [{"id": "native", "engine": "native", "label": "Native"}, {"id": "onnx", "engine": "onnx", "label": "ONNX"}]
        answers = ["2", "2", "6", "y", "n", "y", "n", "y", "n", "y", "n", "y", "y"]
        with patch("builtins.input", side_effect=answers), patch("sys.stdout", new_callable=io.StringIO):
            settings = patcher.configure(models, None, True)
        self.assertEqual(settings, {
            "selected_model": "onnx", "hardware_target": "NPU", "thread_count": 6,
            "preserve_side": True, "two_way_slider": False, "allow_zero_floor": True,
            "force_song_eligibility": False, "center_group_lyrics": True,
            "blur_unfocused_lyrics": False, "english_translation_fallback": True, "eco_mode": False,
            "apple_red_tint": True, "liquid_glass": True,
        })

    def test_old_module_is_rejected_before_claiming_config_support(self):
        with tempfile.TemporaryDirectory() as d:
            module = Path(d) / "old.apk"
            make_apk(module)
            with self.assertRaisesRegex(patcher.PatchError, "lacks patch-default support"):
                patcher.module_support(module, "arm64-v8a")


if __name__ == "__main__":
    unittest.main()
