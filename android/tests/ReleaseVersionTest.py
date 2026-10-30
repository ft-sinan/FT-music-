import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "scripts" / "release-version.py"
spec = importlib.util.spec_from_file_location("release_version", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class ReleaseVersionTest(unittest.TestCase):
    def test_monotonic_code(self):
        self.assertEqual(module.parse_version("0.3.0"), 3000)
        self.assertGreater(module.parse_version("0.3.10"), module.parse_version("0.3.2"))
        self.assertGreater(module.parse_version("1.0.0"), module.parse_version("0.999.999"))

    def test_rejects_unsafe_versions(self):
        for value in ["", "0.0.0", "0.03.0", "0.3", "0.3.0-beta", "2100.0.0", "1.1000.0", "-1.2.3"]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                module.parse_version(value)

    def test_existing_release_cannot_be_overwritten_or_downgraded(self):
        with tempfile.TemporaryDirectory() as temp:
            props = pathlib.Path(temp, "version.properties")
            tags = pathlib.Path(temp, "tags")
            props.write_text("versionName=0.3.0\n", encoding="utf-8")
            tags.write_text("v0.2.1\n", encoding="utf-8")
            args = [sys.executable, str(SCRIPT), "--properties", str(props), "--existing-tags", str(tags)]
            self.assertEqual(subprocess.run(args, capture_output=True).returncode, 0)
            tags.write_text("v0.3.0\n", encoding="utf-8")
            self.assertNotEqual(subprocess.run(args, capture_output=True).returncode, 0)
            tags.write_text("v0.4.0\n", encoding="utf-8")
            self.assertNotEqual(subprocess.run(args, capture_output=True).returncode, 0)

if __name__ == "__main__":
    unittest.main()
