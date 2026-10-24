#!/usr/bin/env python3
"""Build production JVM parsers without Gradle, Android, signing or devices."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / ".fuzz-build"
DEPS = BUILD / "deps"
LOCK = json.loads((ROOT / "fuzz/dependencies.json").read_text())
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


def prepare():
    DEPS.mkdir(parents=True, exist_ok=True)
    for item in LOCK:
        destination = DEPS / item["path"].split("/")[-1]
        if destination.is_file() and not destination.is_symlink() and digest(destination) == item["sha256"]:
            continue
        # Only immutable Maven artifacts are fetched. Exact size/hash are checked
        # before they can enter either the compiler or the test classpath.
        url = "https://repo.maven.apache.org/maven2/" + item["path"]
        with urllib.request.urlopen(url, timeout=30) as response:
            data = response.read(item["bytes"] + 1)
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
