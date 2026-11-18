"""Check immutable dependency acquisition without network or JVM execution."""

import hashlib
import importlib.util
import io
import ssl
import tempfile
import unittest
from datetime import datetime, timezone
from email.message import Message
from pathlib import Path
from unittest.mock import call, patch
from urllib.error import HTTPError, URLError

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("jvm_build", ROOT / "fuzz/build.py")
build = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(build)
URL = "https://repo.maven.apache.org/maven2/example.jar"


def http_error(code, retry_after=None):
    headers = Message()
    if retry_after is not None:
        headers["Retry-After"] = retry_after
    return HTTPError(URL, code, "Test response", headers, io.BytesIO())


class MavenDownloadTests(unittest.TestCase):
    def test_rate_limit_waits_for_server_then_retries_same_url(self):
        limited = http_error(429, "12")
        with (
            patch.object(
                build.urllib.request, "urlopen", side_effect=[limited, io.BytesIO(b"data")]
            ) as request,
            patch.object(build.time, "sleep") as sleep,
        ):
            self.assertEqual(build.download_dependency(URL, 4), b"data")
        self.assertEqual(request.call_args_list, [call(URL, timeout=30)] * 2)
        sleep.assert_called_once_with(12)
        self.assertTrue(limited.fp.closed)

    def test_http_date_delay_is_used_for_temporary_unavailability(self):
        unavailable = http_error(503, "Tue, 17 Nov 2026 12:00:15 GMT")
        with (
            patch.object(build, "datetime", wraps=datetime) as clock,
            patch.object(
                build.urllib.request, "urlopen", side_effect=[unavailable, io.BytesIO(b"data")]
            ),
            patch.object(build.time, "sleep") as sleep,
        ):
            clock.now.return_value = datetime(2026, 11, 17, 12, tzinfo=timezone.utc)
            self.assertEqual(build.download_dependency(URL, 4), b"data")
        sleep.assert_called_once_with(15)

    def test_attempts_are_capped_for_transient_transport_errors(self):
        failures = [URLError(TimeoutError()), ConnectionResetError(), ConnectionAbortedError()]
        with (
            patch.object(build.urllib.request, "urlopen", side_effect=failures) as request,
            patch.object(build.time, "sleep") as sleep,
            self.assertRaises(ConnectionAbortedError),
        ):
            build.download_dependency(URL, 4)
        self.assertEqual(request.call_count, 3)
        self.assertEqual(sleep.call_args_list, [call(2), call(8)])

    def test_server_delay_must_fit_total_wait_budget(self):
        with (
            patch.object(
                build.urllib.request,
                "urlopen",
                side_effect=[http_error(429, "40"), http_error(429, "30")],
            ) as request,
            patch.object(build.time, "sleep") as sleep,
            self.assertRaises(HTTPError),
        ):
            build.download_dependency(URL, 4)
        self.assertEqual(request.call_count, 2)
        sleep.assert_called_once_with(40)

    def test_permanent_errors_fail_without_retry(self):
        for error in (http_error(403), http_error(404), URLError(ssl.SSLCertVerificationError())):
            with (
                self.subTest(error=error),
                patch.object(build.urllib.request, "urlopen", side_effect=error) as request,
                patch.object(build.time, "sleep") as sleep,
                self.assertRaises(type(error)),
            ):
                build.download_dependency(URL, 4)
            request.assert_called_once()
            sleep.assert_not_called()

    def test_wrong_size_or_digest_never_installs_or_starts_compiler(self):
        lock = [{"path": "example.jar", "bytes": 4, "sha256": hashlib.sha256(b"good").hexdigest()}]
        for body in (b"larger", b"bad!"):
            with self.subTest(body=body), tempfile.TemporaryDirectory() as directory:
                deps = Path(directory) / "deps"
                with (
                    patch.object(build, "DEPS", deps),
                    patch.object(build, "LOCK", lock),
                    patch.object(
                        build.urllib.request, "urlopen", return_value=io.BytesIO(body)
                    ) as request,
                    patch.object(build.subprocess, "run") as compiler,
                    self.assertRaisesRegex(ValueError, "Dependency integrity check failed"),
                ):
                    build.prepare()
                self.assertEqual(list(deps.iterdir()), [])
                request.assert_called_once_with(URL, timeout=30)
                compiler.assert_not_called()
