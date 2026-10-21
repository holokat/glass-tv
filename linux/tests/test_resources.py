from pathlib import Path
import tempfile
import unittest

from glasstv_linux.resources import RESOURCE_ROOT_ENVIRONMENT_VARIABLE, asset_path, resource_root


class ResourcePathTests(unittest.TestCase):
    def test_uses_staged_bundle_root_when_launcher_provides_it(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            environment = {RESOURCE_ROOT_ENVIRONMENT_VARIABLE: str(root)}
            self.assertEqual(resource_root(environment), root.resolve())
            self.assertEqual(asset_path("icons/refresh.svg", environment), root.resolve() / "icons" / "refresh.svg")

    def test_rejects_absolute_and_parent_asset_paths(self):
        with self.assertRaises(ValueError):
            asset_path("../secret")
        with self.assertRaises(ValueError):
            asset_path("/etc/passwd")
