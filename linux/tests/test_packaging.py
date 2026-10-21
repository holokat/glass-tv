from pathlib import Path
import unittest


LINUX_DIRECTORY = Path(__file__).resolve().parents[1]
PACKAGING_DIRECTORY = LINUX_DIRECTORY / "packaging"


class AppImageRecipeTests(unittest.TestCase):
    def setUp(self):
        self.recipe = (PACKAGING_DIRECTORY / "build-appimage.sh").read_text(encoding="utf-8")

    def test_supports_full_workspace_and_public_checkout_roots(self):
        self.assertIn('if [ -d "$source_dir/.git" ]; then', self.recipe)
        self.assertIn('[ -d "$source_dir/../release-source/.git" ]', self.recipe)
        self.assertIn('workspace_dir=$source_dir', self.recipe)

    def test_preserves_existing_files_and_uses_unique_staging(self):
        self.assertNotIn("rm -rf", self.recipe)
        self.assertNotIn('rm -f "$artifact"', self.recipe)
        self.assertIn('build_root=$(mktemp -d "$work_root/build.XXXXXXXX")', self.recipe)
        self.assertIn('Candidate output already exists', self.recipe)

    def test_handles_absent_private_icon_and_imports_package_from_linux_root(self):
        self.assertIn('if [ -f "$source_dir/shared/icons/refresh.svg" ]; then', self.recipe)
        self.assertIn('PYTHONPATH="$linux_dir${PYTHONPATH:+:$PYTHONPATH}" "$python_bin" -m nuitka', self.recipe)

    def test_readme_has_portable_environment_setup(self):
        readme = (LINUX_DIRECTORY / "README.md").read_text(encoding="utf-8")
        self.assertNotIn("/Users/", readme)
        self.assertIn("${XDG_DATA_HOME:-$HOME/.local/share}", readme)
        self.assertIn("No command in this document depends on a developer-specific path.", readme)
