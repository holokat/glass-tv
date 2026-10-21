import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from glasstv_linux.paths import APP_DIRECTORY, AppPaths, LocalSettings, SettingsError, StoragePathError


class AppPathsTests(unittest.TestCase):
    def test_uses_isolated_xdg_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paths = AppPaths.from_environment(
                {
                    "XDG_CONFIG_HOME": str(root / "config"),
                    "XDG_DATA_HOME": str(root / "data"),
                    "XDG_STATE_HOME": str(root / "state"),
                    "XDG_CACHE_HOME": str(root / "cache"),
                },
                home=root / "home",
            )
            self.assertEqual(paths.config, root / "config" / APP_DIRECTORY)
            self.assertEqual(paths.data, root / "data" / APP_DIRECTORY)
            self.assertEqual(paths.state, root / "state" / APP_DIRECTORY)
            self.assertEqual(paths.cache, root / "cache" / APP_DIRECTORY)
            paths.ensure_directories()
            self.assertTrue(all(path.is_dir() for path in (paths.config, paths.data, paths.state, paths.cache)))

    def test_rejects_relative_xdg_override(self):
        with self.assertRaises(StoragePathError):
            AppPaths.from_environment({"XDG_CONFIG_HOME": "relative"}, home=Path("/tmp/home"))


class LocalSettingsTests(unittest.TestCase):
    def test_writes_canonical_json_atomically(self):
        with tempfile.TemporaryDirectory() as temporary:
            store = LocalSettings(Path(temporary) / "settings.json")
            store.save({"z": 1, "a": ["one", "two"]})
            self.assertEqual(store.load(), {"z": 1, "a": ["one", "two"]})
            self.assertEqual(json.loads(store.path.read_text(encoding="utf-8")), {"z": 1, "a": ["one", "two"]})
            self.assertEqual(list(Path(temporary).glob(".settings-*.tmp")), [])

    def test_replace_failure_preserves_previous_settings_and_removes_temporary_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            store = LocalSettings(Path(temporary) / "settings.json")
            store.save({"version": "old"})
            with mock.patch("glasstv_linux.paths.os.replace", side_effect=OSError("injected failure")):
                with self.assertRaises(SettingsError):
                    store.save({"version": "new"})
            self.assertEqual(store.load(), {"version": "old"})
            self.assertEqual(list(Path(temporary).glob(".settings-*.tmp")), [])

    def test_rejects_nonfinite_json_and_nonobject_load(self):
        with tempfile.TemporaryDirectory() as temporary:
            store = LocalSettings(Path(temporary) / "settings.json")
            with self.assertRaises(SettingsError):
                store.save({"value": float("nan")})
            store.path.parent.mkdir(exist_ok=True)
            store.path.write_text("[]", encoding="utf-8")
            with self.assertRaises(SettingsError):
                store.load()
