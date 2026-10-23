"""Exercise the actual aggregate gate with successful and incomplete executions."""

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/check-ci-gate.py"
spec = importlib.util.spec_from_file_location("ci_gate", SCRIPT)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def dependencies(*, docs=False, devices=False):
    return {
        "scope": {"result": "success", "outputs": {"run": "false" if docs else "true", "reason": "documentation-only" if docs else "required-change"}},
        "build": {"result": "skipped" if docs else "success"},
        "emulator": {"result": "success" if devices and not docs else "skipped"},
    }


class AndroidGateTests(unittest.TestCase):
    def test_pr_and_manual_host_checks_pass_without_device_jobs(self):
        for event in ("pull_request", "workflow_dispatch"):
            with self.subTest(event=event):
                gate.validate(dependencies(), event)

    def test_integration_and_explicit_dispatch_require_device_matrix(self):
        for event, enabled in (("push", False), ("merge_group", False), ("workflow_dispatch", True)):
            with self.subTest(event=event):
                gate.validate(dependencies(devices=True), event, enabled)
                for state in ("failure", "cancelled", "skipped", None):
                    needs = dependencies(devices=True)
                    needs["emulator"]["result"] = state
                    with self.assertRaises(ValueError):
                        gate.validate(needs, event, enabled)

    def test_only_explicit_docs_only_scope_permits_build_skip(self):
        for event in ("pull_request", "push", "merge_group", "workflow_dispatch"):
            gate.validate(dependencies(docs=True), event)
        for run, reason in (("false", "unknown"), ("false", ""), ("true", "documentation-only")):
            needs = dependencies(docs=True)
            needs["scope"]["outputs"] = {"run": run, "reason": reason}
            with self.subTest(run=run, reason=reason), self.assertRaises(ValueError):
                gate.validate(needs, "pull_request")

    def test_failed_cancelled_or_missing_dependencies_never_pass(self):
        for docs in (False, True):
            for name in ("scope", "build", "emulator"):
                for state in ("failure", "cancelled", None, ""):
                    needs = dependencies(docs=docs)
                    needs[name]["result"] = state
                    with self.subTest(docs=docs, name=name, state=state), self.assertRaises(ValueError):
                        gate.validate(needs, "pull_request")
                needs = dependencies(docs=docs)
                del needs[name]
                with self.assertRaises(ValueError):
                    gate.validate(needs, "pull_request")

    def test_unknown_scope_event_or_input_fails_closed(self):
        for outputs in ({}, None, {"run": True}, {"run": "maybe"}):
            needs = dependencies()
            needs["scope"]["outputs"] = outputs
            with self.subTest(outputs=outputs), self.assertRaises(ValueError):
                gate.validate(needs, "pull_request")
        with self.assertRaises(ValueError):
            gate.validate(dependencies(), "pull_request_target")
        with self.assertRaises(ValueError):
            gate.validate(dependencies(), "workflow_dispatch", "false")
        needs = dependencies()
        needs["unexpected"] = {"result": "success"}
        with self.assertRaises(ValueError):
            gate.validate(needs, "pull_request")

    def test_command_exit_code_matches_real_environment_contract(self):
        env = dict(os.environ, ANDROID_EVENT="pull_request", ANDROID_DEVICE_TESTS="false")
        env["ANDROID_NEEDS"] = json.dumps(dependencies())
        good = subprocess.run([sys.executable, str(SCRIPT)], env=env, capture_output=True, text=True)
        self.assertEqual(good.returncode, 0, good.stderr)
        bad = dependencies()
        bad["scope"]["result"] = "failure"
        env["ANDROID_NEEDS"] = json.dumps(bad)
        self.assertNotEqual(subprocess.run([sys.executable, str(SCRIPT)], env=env, capture_output=True).returncode, 0)


if __name__ == "__main__":
    unittest.main()
