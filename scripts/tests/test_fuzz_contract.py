"""Prevent offline target dependencies drifting from the app's parser runtime."""

import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class FuzzContractTests(unittest.TestCase):
    def test_compiler_and_direct_dependencies_match_the_app(self):
        lock = json.loads((ROOT / "fuzz/dependencies.json").read_text())
        paths = {entry["path"] for entry in lock}
        version = re.search(r'kotlin\("jvm"\) version "([^"]+)"', (ROOT / "build.gradle.kts").read_text())[1]
        self.assertIn(f"org/jetbrains/kotlin/kotlin-compiler-embeddable/{version}/kotlin-compiler-embeddable-{version}.jar", paths)
        source = (ROOT / "core/build.gradle.kts").read_text()
        for group, artifact, version in re.findall(r'implementation\("([^:"]+):([^:"]+):([^"]+)"\)', source):
            # Multiplatform coordinates resolve to their JVM artifacts here.
            artifact += "-jvm"
            self.assertIn(f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.jar", paths)
        for entry in lock:
            self.assertRegex(entry["sha256"], r"^[a-f0-9]{64}$")
            self.assertGreater(entry["bytes"], 0)
            self.assertLess(entry["bytes"], 100 * 1024 * 1024)
        self.assertEqual(len(paths), len(lock))

    def test_seed_inputs_are_bounded_and_not_links(self):
        for directory in (ROOT / "fuzz/corpus").iterdir():
            seeds = list(directory.iterdir())
            self.assertGreaterEqual(len(seeds), 4)
            for seed in seeds:
                self.assertFalse(seed.is_symlink())
                self.assertTrue(seed.is_file())
                self.assertLessEqual(seed.stat().st_size, 16384)


if __name__ == "__main__":
    unittest.main()
