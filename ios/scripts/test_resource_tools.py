"""Host-side failure-path tests. These do not execute XCTest or native iOS code."""
import hashlib
import tempfile
import unittest
from pathlib import Path
from prepare_resources import verify


class ResourceGuardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="shadow-ios-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "assets"
        self.root.mkdir()
        self.file = self.root / "model.bin"
        self.file.write_bytes(b"verified-model")
        self.spec = {"bytes": self.file.stat().st_size, "sha256": hashlib.sha256(self.file.read_bytes()).hexdigest()}

    def test_valid_asset(self):
        self.assertEqual(verify(self.root, {"files": {"model.bin": self.spec}}), 1)

    def test_same_size_corruption_is_rejected(self):
        self.file.write_bytes(b"corrupt!-model")
        self.assertEqual(self.file.stat().st_size, self.spec["bytes"])
        with self.assertRaisesRegex(ValueError, "Corrupt asset"):
            verify(self.root, {"files": {"model.bin": self.spec}})

    def test_truncated_asset_is_rejected(self):
        self.file.write_bytes(b"short")
        with self.assertRaisesRegex(ValueError, "Corrupt asset"):
            verify(self.root, {"files": {"model.bin": self.spec}})

    def test_parent_escape_is_rejected_even_with_correct_hash(self):
        (self.root.parent / "outside.bin").write_bytes(b"verified-model")
        with self.assertRaisesRegex(ValueError, "unsafe asset"):
            verify(self.root, {"files": {"../outside.bin": self.spec}})

    def test_absolute_escape_is_rejected(self):
        outside = self.root.parent / "absolute.bin"
        outside.write_bytes(b"verified-model")
        with self.assertRaisesRegex(ValueError, "unsafe asset"):
            verify(self.root, {"files": {str(outside): self.spec}})

    def test_missing_file_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Missing"):
            verify(self.root, {"files": {"missing.bin": self.spec}})


if __name__ == "__main__":
    unittest.main()
