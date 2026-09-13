import importlib.util
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("release", ROOT / "scripts/release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseBoundariesTest(unittest.TestCase):
    def info(self):
        return dict(channel="preview", versionName="0.2.0-preview", versionCode=2,
                    baseVersion="0.2.0", applicationId="play.ott.foss.nativeapp.preview",
                    tag="v0.2.0-preview.2", commit="a" * 40)

    def preflight(self, tags=(), jobs=None, dirty=False):
        required = ["Unit tests, lint and APKs", "Instrumented tests · phone-api35", "Instrumented tests · androidtv-api36"]
        def command(*args, **kwargs):
            if args[:3] == ("git", "status", "--porcelain"):
                return " M source.kt\n" if dirty else ""
            return json.dumps([[dict(name=tag) for tag in tags]])
        def api(path):
            if path.endswith("/jobs?per_page=100"):
                return {"jobs": jobs if jobs is not None else [dict(name=name, conclusion="success") for name in required]}
            return {"workflow_runs": [dict(id=42, event="workflow_dispatch", html_url="https://github.com/example/app/actions/runs/42")]}
        with patch.object(release, "run", side_effect=command), patch.object(release, "gh", side_effect=api), patch.dict(os.environ, {}, clear=True):
            info = self.info()
            release.preflight(info, "example/app")
            return info

    def test_requires_both_real_os_jobs_not_only_green_host(self):
        with self.assertRaisesRegex(ValueError, "No successful host"):
            self.preflight(jobs=[dict(name="Unit tests, lint and APKs", conclusion="success")])

    def test_failed_or_skipped_device_job_cannot_authorize_publish(self):
        for conclusion in ("failure", "skipped", "cancelled"):
            with self.subTest(conclusion=conclusion), self.assertRaises(ValueError):
                self.preflight(jobs=[dict(name="Unit tests, lint and APKs", conclusion="success"),
                                     dict(name="Instrumented tests · phone-api35", conclusion="success"),
                                     dict(name="Instrumented tests · androidtv-api36", conclusion=conclusion)])

    def test_existing_tag_and_reused_preview_code_are_rejected(self):
        for tag in ("v0.2.0-preview.2", "v0.1.0-preview.2", "v0.2.1-preview.1"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                self.preflight(tags=[tag])

    def test_uncommitted_sources_cannot_be_released(self):
        with self.assertRaisesRegex(ValueError, "clean committed"):
            self.preflight(dirty=True)

    def test_prior_preview_and_current_full_matrix_are_accepted(self):
        self.assertIn("validatedRun", self.preflight(tags=["v0.1.0-preview.1"]))

    def test_production_does_not_fall_back_to_preview_secret_values(self):
        with tempfile.TemporaryDirectory() as directory:
            env = dict(os.environ, RELEASE_CHANNEL="production", RUNNER_TEMP=directory,
                       GITHUB_ENV=str(Path(directory) / "env"))
            for key in tuple(env):
                if key.startswith("RELEASE_SIGNING_"):
                    del env[key]
            for field in ("KEYSTORE_BASE64", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD", "CERT_SHA256"):
                env["PREVIEW_SIGNING_" + field] = "private-test-marker"
            result = subprocess.run([sys.executable, str(ROOT / "scripts/ci-prepare-signing.py")], env=env, text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Signing is not configured", result.stderr)
            self.assertNotIn("private-test-marker", result.stdout + result.stderr)
            self.assertFalse((Path(directory) / "env").exists())

    def test_missing_signing_identity_never_builds_unsigned_preview(self):
        with patch.dict(os.environ, {}, clear=True), self.assertRaisesRegex(ValueError, "no debug/unsigned fallback"):
            release.signing_environment("preview", None)

    def test_signed_build_verifies_both_formats_and_emits_matching_checksums(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            keystore = root / "synthetic.keystore"
            keystore.write_bytes(b"synthetic fixture, not a private key")
            env = dict(OTTPLAY_KEYSTORE=str(keystore), OTTPLAY_STORE_PASSWORD="fixture",
                       OTTPLAY_KEY_ALIAS="fixture", OTTPLAY_KEY_PASSWORD="fixture",
                       OTTPLAY_EXPECTED_CERT_SHA256="b" * 64, ANDROID_HOME=str(root / "sdk"))
            calls = []
            def command(*args, **kwargs):
                calls.append(args)
                if args[0] == "./gradlew":
                    for kind, extension in (("apk", "apk"), ("bundle", "aab")):
                        destination = root / f"app/build/outputs/{kind}/preview/app-preview.{extension}"
                        destination.parent.mkdir(parents=True)
                        destination.write_bytes(b"synthetic " + extension.encode())
                elif args[0].endswith("apksigner"):
                    return "Signer #1 certificate SHA-256 digest: " + "b" * 64
                elif args[0].endswith("aapt2"):
                    return "package: name='play.ott.foss.nativeapp.preview' versionCode='2' versionName='0.2.0-preview'"
                elif args[0] == "jarsigner":
                    return "jar verified."
                elif args[0] == "keytool":
                    return "SHA256: " + ":".join(["BB"] * 32)
                return ""
            with patch.object(release, "ROOT", root), patch.object(release, "run", side_effect=command), patch.dict(os.environ, env, clear=True), contextlib.redirect_stdout(io.StringIO()):
                release.build(self.info(), None)
            out = root / "build/distributions/v0.2.0-preview.2"
            manifest = json.loads((out / "release-manifest.json").read_text())
            self.assertEqual(manifest["signingCertificateSha256"], "b" * 64)
            self.assertEqual(len(manifest["artifacts"]), 2)
            for line in (out / "SHA256SUMS").read_text().splitlines():
                expected, name = line.split("  ", 1)
                self.assertEqual(hashlib.sha256((out / name).read_bytes()).hexdigest(), expected)
            self.assertIn(":app:testDebugUnitTest", calls[0])
            self.assertIn(":app:lintPreview", calls[0])
            self.assertTrue(any(call[0] == "jarsigner" and "-strict" in call for call in calls))

    def test_publish_tag_creation_race_does_not_create_or_overwrite_release(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            out = root / "build/distributions/v0.2.0-preview.2"
            out.mkdir(parents=True)
            apk = out / "fixture.apk"
            apk.write_bytes(b"synthetic apk")
            info = self.info()
            manifest = info | {"artifacts": [dict(name=apk.name, sha256=hashlib.sha256(apk.read_bytes()).hexdigest())]}
            (out / "release-manifest.json").write_text(json.dumps(manifest))
            calls = []
            def preflight(current, repository):
                current["validatedRun"] = "https://github.com/example/app/actions/runs/42"
            def command(*args, **kwargs):
                calls.append(args)
                raise subprocess.CalledProcessError(1, args)  # GitHub refuses a concurrently created ref.
            with patch.object(release, "ROOT", root), patch.object(release, "preflight", side_effect=preflight), patch.object(release, "run", side_effect=command):
                with self.assertRaises(subprocess.CalledProcessError):
                    release.publish(info, "example/app")
            self.assertEqual(len(calls), 1)
            self.assertEqual(calls[0][:4], ("gh", "api", "--method", "POST"))
            self.assertNotIn("--force", calls[0])

    def test_modified_artifact_is_rejected_before_tag_creation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            out = root / "build/distributions/v0.2.0-preview.2"
            out.mkdir(parents=True)
            (out / "fixture.apk").write_bytes(b"changed")
            info = self.info()
            (out / "release-manifest.json").write_text(json.dumps(info | {"artifacts": [dict(name="fixture.apk", sha256="0" * 64)]}))
            with patch.object(release, "ROOT", root), patch.object(release, "preflight"), patch.object(release, "run") as command:
                with self.assertRaisesRegex(ValueError, "checksum changed"):
                    release.publish(info, "example/app")
                command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
