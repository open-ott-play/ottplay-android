#!/usr/bin/env python3
"""Require the Android jobs selected by this workflow's change scope and event."""

import json
import os


def validate(needs, event, run_device_tests=False):
    if event not in {"pull_request", "push", "merge_group", "workflow_dispatch"}:
        raise ValueError("Unknown Android workflow event")
    if not isinstance(needs, dict) or set(needs) != {"scope", "build", "emulator"}:
        raise ValueError("Android gate requires exactly scope, build and emulator results")
    if any(not isinstance(job, dict) for job in needs.values()):
        raise ValueError("Malformed dependency result")
    if needs["scope"].get("result") != "success":
        raise ValueError("Change scope must succeed")
    outputs = needs["scope"].get("outputs", {})
    if not isinstance(outputs, dict) or outputs.get("run") not in {"true", "false"}:
        raise ValueError("Change scope must report its decision")
    docs_only = outputs.get("run") == "false" and outputs.get("reason") == "documentation-only"
    if outputs.get("run") == "false" and not docs_only:
        raise ValueError("Only an explicit documentation-only decision may skip Android jobs")
    if not isinstance(run_device_tests, bool):
        raise ValueError("Device-test input must be boolean")
    if docs_only:
        expected = {"build": "skipped", "emulator": "skipped"}
    else:
        devices_required = event in {"push", "merge_group"} or run_device_tests
        expected = {"build": "success", "emulator": "success" if devices_required else "skipped"}
    for name, result in expected.items():
        if needs[name].get("result") != result:
            raise ValueError(f"{name} must be {result}; got {needs[name].get('result')!r}")


def main():
    validate(
        json.loads(os.environ["ANDROID_NEEDS"]),
        os.environ["ANDROID_EVENT"],
        json.loads(os.environ.get("ANDROID_DEVICE_TESTS", "false")),
    )
    print("Android CI gate passed")


if __name__ == "__main__":
    main()
