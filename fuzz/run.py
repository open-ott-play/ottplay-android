#!/usr/bin/env python3
"""Exec the pinned Jazzer launcher; the shared runner supplies campaign limits."""

import os
import sys

from build import runtime_classpath

targets = {"playlist": "PlaylistFuzz", "xmltv": "XmltvFuzz"}
if len(sys.argv) < 2 or sys.argv[1] not in targets:
    raise SystemExit("Usage: python3 fuzz/run.py {playlist|xmltv} [libFuzzer flags]")
os.execvp("java", [
    "java", "-Xmx1024m", "-cp", runtime_classpath(), "com.code_intelligence.jazzer.Jazzer",
    "--target_class=play.ott.nativeapp.core." + targets[sys.argv[1]],
    "--instrumentation_includes=play.ott.nativeapp.core.**:play.ott.core.**",
    "--hooks=true", "-max_len=16384", *sys.argv[2:],
])
