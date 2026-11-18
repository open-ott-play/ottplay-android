#!/usr/bin/env python3
"""Build production JVM parsers without Gradle, Android, signing or devices."""

import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / ".fuzz-build"
DEPS = BUILD / "deps"
LOCK = json.loads((ROOT / "fuzz/dependencies.json").read_text())
RETRY_DELAYS = (2, 8)
MAX_RETRY_SLEEP = 60
RETRYABLE_CONNECTION_ERRORS = (TimeoutError, ConnectionResetError, ConnectionAbortedError)
DOWNLOAD_ERRORS = (urllib.error.URLError,) + RETRYABLE_CONNECTION_ERRORS
SOURCES = [
    "Models.kt", "CoreMessage.kt", "DrmConfig.kt", "KodiDrmParser.kt",
    "M3uParser.kt", "XmltvParser.kt", "HeaderOrigins.kt", "ProviderHttp.kt",
    "RemoteTransportPolicy.kt", "TlsCertificateKeyPolicy.kt",
]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def classpath(roles):
    return os.pathsep.join(str(DEPS / item["path"].split("/")[-1])
                           for item in LOCK if set(item["roles"]) & set(roles))


def retry_after_seconds(header):
    if not header:
        return 0
    value = header.strip()
    if value.isascii() and value.isdecimal():
        return float(value)
    try:
        retry_at = parsedate_to_datetime(value)
    except (ValueError, OverflowError):
        return 0
    if retry_at.tzinfo is None:
        retry_at = retry_at.replace(tzinfo=timezone.utc)
    return max(0, (retry_at - datetime.now(timezone.utc)).total_seconds())


def download_dependency(url, size):
    remaining_sleep = MAX_RETRY_SLEEP
    for delay in (*RETRY_DELAYS, None):
        try:
            with urllib.request.urlopen(url, timeout=30) as response:
                return response.read(size + 1)
        except urllib.error.HTTPError as error:
            error.close()
            if delay is None or error.code not in (429, 503):
                raise
            header = error.headers.get("Retry-After") if error.headers is not None else None
            delay = max(delay, retry_after_seconds(header))
            if delay > remaining_sleep:
                raise
        except DOWNLOAD_ERRORS as error:
            reason = error.reason if isinstance(error, urllib.error.URLError) else error
            if delay is None or not isinstance(reason, RETRYABLE_CONNECTION_ERRORS):
                raise
            if delay > remaining_sleep:
                raise
        time.sleep(delay)
        remaining_sleep -= delay


def prepare():
    DEPS.mkdir(parents=True, exist_ok=True)
    for item in LOCK:
        destination = DEPS / item["path"].split("/")[-1]
        if destination.is_file() and not destination.is_symlink() and digest(destination) == item["sha256"]:
            continue
        # Only immutable Maven artifacts are fetched. Exact size/hash are checked
        # before they can enter either the compiler or the test classpath.
        url = "https://repo.maven.apache.org/maven2/" + item["path"]
        data = download_dependency(url, item["bytes"])
        if len(data) != item["bytes"] or hashlib.sha256(data).hexdigest() != item["sha256"]:
            raise ValueError("Dependency integrity check failed: " + item["path"])
        temporary = destination.with_suffix(".download")
        temporary.write_bytes(data)
        temporary.replace(destination)
    artifact = ROOT / "core/vendor/ottplay-core.jar"
    manifest = json.loads((ROOT / "core/vendor/ottplay-core.manifest.json").read_text())
    if digest(artifact) != manifest["artifacts"][artifact.name]["sha256"]:
        raise ValueError("Shared core JAR does not match its production manifest")
    source = ROOT / "core/src/main/kotlin/play/ott/nativeapp/core"
    subprocess.run([
        "java", "-Xmx1024m", "-cp", classpath(["compiler"]),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-jvm-target", "17",
        "-no-stdlib", "-no-reflect", "-classpath", classpath(["runtime"]) + os.pathsep + str(artifact),
        "-d", str(BUILD / "targets.jar"),
        *(str(source / name) for name in SOURCES), str(ROOT / "fuzz/ParserTargets.kt"),
    ], check=True, cwd=ROOT, timeout=120)


def runtime_classpath():
    return os.pathsep.join([classpath(["runtime", "fuzzer"]), str(BUILD / "targets.jar"),
                            str(ROOT / "core/vendor/ottplay-core.jar")])


if __name__ == "__main__":
    mode = sys.argv[1:]
    if mode not in ([], ["replay"]):
        raise SystemExit("Usage: python3 fuzz/build.py [replay]")
    prepare()
    if mode == ["replay"]:
        subprocess.run(["java", "-Xmx1024m", "-cp", runtime_classpath(),
                        "play.ott.nativeapp.core.ReplaySeeds", str(ROOT / "fuzz/corpus")],
                       check=True, cwd=ROOT, timeout=60)
