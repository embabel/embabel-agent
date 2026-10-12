"""Apache-2.0; adapted from the isolated CLEF bridge's six contract tests."""
from contextlib import ExitStack
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

import clef_bridge as bridge
from clef_bridge import BridgeError, ClefRuntime, Config, compile_request, decode_prediction


def request():
    return {"state": "A sourced museum has dates overlapping the trip.", "questions": [
        {"id": "usable", "kind": "proposition", "instructions": "Is it usable?"},
        {"id": "next", "kind": "choice", "instructions": "What next?", "options": [
            {"id": "prioritize", "description": "Research complete"}, {"id": "review", "description": "Research missing"}]},
        {"id": "effort", "kind": "rating", "instructions": "Effort?", "levels": [
            {"id": "easy", "description": "Easy"}, {"id": "hard", "description": "Hard"}]},
        {"id": "setting", "kind": "classification", "instructions": "Setting?", "options": [
            {"id": "indoor", "description": "Museum"}, {"id": "outdoor", "description": "Park"}]}]}


def prediction():
    return {"tokens": 170, "ms": 42.5, "answers": {
        "usable": {"true": .8, "false": .2}, "next": {"prioritize": .7, "review": .3},
        "effort": {"0": .25, "1": .75}, "setting": {"indoor": .9, "outdoor": .1}}}


class FakeArtifacts:
    """Run the Python checker on any host without changing the production guard."""
    def __init__(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.model = self.root / "model"
        self.scratch = self.root / "scratch"
        self.scratch.mkdir()
        self.hashes = {}
        for relative in bridge.PINNED_HASHES:
            path = self.model / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            content = relative.encode()
            path.write_bytes(content)
            self.hashes[relative] = hashlib.sha256(content).hexdigest()
        self.patch = mock.patch.dict(bridge.PINNED_HASHES, self.hashes, clear=True)
        self.provenance = self.root / "provenance.json"
        self.provenance.write_text(json.dumps({
            "source_repo": bridge.SOURCE_REPO, "source_revision": bridge.SOURCE_REVISION,
            "variant": bridge.VARIANT, "license": "Apache-2.0", "tool": "coremltools 9.0",
            "files": {"Decoder": {"derived_spec_sha256": self.hashes[bridge.DECODER_SPEC],
                                  "weights_sha256": self.hashes[bridge.DECODER_WEIGHTS]},
                      "Head": {"derived_spec_sha256": self.hashes[bridge.HEAD_SPEC],
                               "weights_sha256": self.hashes[bridge.HEAD_WEIGHTS]}}}))
        self.checker = self.root / "checker"
        program = r'''
import json, os, pathlib, subprocess, sys, time
root = pathlib.Path(ROOT)
mode = (root / 'mode').read_text() if (root / 'mode').exists() else 'normal'
model = pathlib.Path(sys.argv[1])
record = json.loads(pathlib.Path(sys.argv[2]).read_text())
(root / 'started').write_text(str(os.getpid()))
with (root / 'calls').open('a') as trace:
    trace.write(str(os.getpid()) + '\n')
if (model / 'compiled').exists():
    raise RuntimeError('stale cache')
for p in FILES:
    assert (model / p).is_symlink(), p
assert os.environ['CLEF_COMPUTE'] == 'cpu'
assert os.environ['CLEF_SINGLE_MAIN'] == '1'
assert pathlib.Path(os.environ['TMPDIR']).parent == model.parent
(root / 'probe').write_text(json.dumps({'model': str(model), 'record': record}))
(model / 'compiled').mkdir()
(model / 'compiled' / 'fixture').write_text('new')
if mode == 'gate':
    while not (root / 'release').exists():
        time.sleep(.01)
if mode == 'slow':
    child = subprocess.Popen([sys.executable, '-c', 'import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(60)'])
    (root / 'descendant').write_text(str(child.pid))
    time.sleep(60)
if mode == 'huge':
    sys.stdout.write('x' * 131072); sys.stdout.flush(); time.sleep(60)
if mode == 'stderr':
    sys.stderr.write('SECRET_INPUT' * 131072); sys.stderr.flush(); time.sleep(60)
if mode == 'bad':
    print('SECRET_INPUT'); sys.exit(0)
if mode == 'exit':
    print('SECRET_INPUT', file=sys.stderr); sys.exit(7)
answers = {}
for qid, q in record['questions']:
    if q['type'] == 'noul':
        answers[qid] = {'true': .8, 'false': .2}
    else:
        keys = [str(i) for i in range(len(q['criteria']))] if q['type'] == 'score' else list(q['criteria'])
        answers[qid] = {key: 1 / len(keys) for key in keys}
print(json.dumps({'tokens': 170, 'ms': 1.0, 'answers': answers}))
'''
        program = program.replace('ROOT', repr(str(self.root))).replace('FILES', repr(list(self.hashes)))
        self.checker.write_text('#!' + sys.executable + '\n' + program)
        self.checker.chmod(0o700)
        self.config = Config(checker=self.checker, model_dir=self.model, provenance=self.provenance,
                             scratch_dir=self.scratch, checker_sha256=hashlib.sha256(self.checker.read_bytes()).hexdigest(),
                             checker_source_revision=bridge.RUNTIME_REVISION, checker_build_id="fixture", timeout_seconds=2)
        self.runtime = ClefRuntime(self.config)
        # Install overrides only after artifacts are ready, and unwind partial setup.
        with ExitStack() as patches:
            patches.enter_context(self.patch)
            patches.enter_context(mock.patch.object(bridge.platform, "system", return_value="Darwin"))
            patches.enter_context(mock.patch.object(bridge.platform, "machine", return_value="arm64"))
            patches.enter_context(mock.patch.object(bridge.platform, "mac_ver", return_value=("15.0", ("", "", ""), "")))
            self.patches = patches.pop_all()

    def mode(self, value):
        (self.root / "mode").write_text(value)

    def wait_started(self, path="started"):
        end = time.monotonic() + 3
        while time.monotonic() < end:
            if (self.root / path).exists():
                return
            time.sleep(.01)
        raise AssertionError("fake checker did not start")

    def close(self):
        try:
            self.runtime.close()
        finally:
            try:
                self.patches.close()
            finally:
                self.temp.cleanup()


class FakeArtifactsTests(unittest.TestCase):
    def test_supported_fixture_restores_unsupported_host_and_hashes(self):
        original_hashes = dict(bridge.PINNED_HASHES)
        predicate = bridge._platform_supported
        with mock.patch.object(bridge.platform, "system", return_value="Linux") as system, \
                mock.patch.object(bridge.platform, "machine", return_value="x86_64") as machine, \
                mock.patch.object(bridge.platform, "mac_ver", return_value=("", ("", "", ""), "")) as mac_ver:
            self.assertFalse(predicate())
            fixture = FakeArtifacts()
            try:
                self.assertIs(bridge._platform_supported, predicate)
                self.assertTrue(predicate())
                self.assertTrue(fixture.runtime.infer(request())["ok"])
            finally:
                fixture.close()
            self.assertIs(bridge.platform.system, system)
            self.assertIs(bridge.platform.machine, machine)
            self.assertIs(bridge.platform.mac_ver, mac_ver)
            self.assertEqual(bridge.PINNED_HASHES, original_hashes)
            self.assertFalse(predicate())
            with mock.patch.object(bridge.subprocess, "Popen") as launch:
                with self.assertRaises(BridgeError) as error:
                    fixture.runtime.infer(request())
                self.assertEqual(error.exception.code, "unsupported_platform")
                launch.assert_not_called()
            self.assertFalse(fixture.root.exists())

    def test_cleanup_failure_still_restores_platform_hashes_and_temporary_files(self):
        original_hashes = dict(bridge.PINNED_HASHES)
        with mock.patch.object(bridge.platform, "system", return_value="Linux") as system, \
                mock.patch.object(bridge.platform, "machine", return_value="x86_64") as machine, \
                mock.patch.object(bridge.platform, "mac_ver", return_value=("", ("", "", ""), "")) as mac_ver:
            for failing in ["runtime", "temporary"]:
                with self.subTest(failing=failing):
                    fixture = FakeArtifacts()
                    target = fixture.runtime if failing == "runtime" else fixture.temp
                    method = "close" if failing == "runtime" else "cleanup"
                    cleanup = getattr(target, method)
                    def fail_after_cleanup():
                        cleanup()
                        raise RuntimeError("fixture cleanup failed")
                    with mock.patch.object(target, method, side_effect=fail_after_cleanup):
                        with self.assertRaisesRegex(RuntimeError, "fixture cleanup failed"):
                            fixture.close()
                    self.assertIs(bridge.platform.system, system)
                    self.assertIs(bridge.platform.machine, machine)
                    self.assertIs(bridge.platform.mac_ver, mac_ver)
                    self.assertEqual(bridge.PINNED_HASHES, original_hashes)
                    self.assertFalse(bridge._platform_supported())
                    self.assertFalse(fixture.root.exists())


class BridgeSerializationTests(unittest.TestCase):
    def test_compiles_all_four_families_and_maps_rating_indices(self):
        wire, questions = compile_request(request())
        self.assertEqual([q[1]["type"] for q in wire["questions"]], ["noul", "choice", "score", "choice"])
        self.assertEqual(wire["questions"][2][1]["criteria"], ["Easy", "Hard"])
        result = decode_prediction(prediction(), questions)
        self.assertEqual([a["selected_id"] for a in result["answers"]], ["true", "prioritize", "hard", "indoor"])
        self.assertEqual(result["answers"][2]["probabilities"], {"easy": .25, "hard": .75})
        self.assertEqual(result["answers"][2]["expected_index"], .75)

    def test_rejects_unsupported_ranking_and_duplicate_ids(self):
        value = request(); value["questions"][0]["kind"] = "ranking"
        with self.assertRaisesRegex(BridgeError, "unsupported question kind"):
            compile_request(value)
        value = request(); value["questions"][1]["id"] = "usable"
        with self.assertRaisesRegex(BridgeError, "duplicate question id"):
            compile_request(value)

    def test_rejects_missing_probabilities_and_possible_truncation(self):
        _, questions = compile_request(request())
        with self.assertRaisesRegex(BridgeError, "checker answer ids"):
            decode_prediction({"tokens": 100, "ms": 1.0, "answers": {}}, questions)
        value = prediction(); value["tokens"] = 1024
        with self.assertRaisesRegex(BridgeError, "1024-token bucket"):
            decode_prediction(value, questions)

    def test_rejects_overlarge_state_and_bad_probability(self):
        value = request(); value["state"] = "x" * 4097
        with self.assertRaisesRegex(BridgeError, "state exceeds"):
            compile_request(value)
        value = prediction(); value["answers"]["usable"]["true"] = float("nan")
        with self.assertRaisesRegex(BridgeError, "invalid probability"):
            decode_prediction(value, compile_request(request())[1])

    def test_cli_returns_typed_failure_without_loading_model(self):
        with tempfile.TemporaryDirectory() as directory:
            args = [sys.executable, str(Path(__file__).with_name("clef_bridge.py")),
                    "--checker", directory, "--model-dir", directory, "--provenance", directory,
                    "--scratch-dir", directory, "--checker-sha256", "0" * 64,
                    "--checker-source-revision", bridge.RUNTIME_REVISION, "--checker-build-id", "test"]
            value = request(); value["questions"][0]["kind"] = "ranking"
            completed = subprocess.run(args, input=json.dumps(value).encode(), capture_output=True, timeout=3)
            self.assertEqual(completed.returncode, 1)
            self.assertEqual(json.loads(completed.stdout)["error"]["code"], "invalid_request")

    def test_checker_timeout_is_typed(self):
        fixture = FakeArtifacts()
        self.addCleanup(fixture.close)
        with mock.patch.object(fixture.runtime, "_run_checker", side_effect=BridgeError("timeout")):
            with self.assertRaises(BridgeError) as error:
                fixture.runtime.infer(request())
        self.assertEqual(error.exception.code, "timeout")

    def test_probability_fixture_inside_and_outside_shared_tolerance_without_normalizing(self):
        questions = compile_request(request())[1]
        for last, valid in [(.75, True), (.7499995, True), (.749998, False), (.7500005, True), (.750002, False)]:
            with self.subTest(last=last):
                value = prediction(); value["answers"]["effort"] = {"0": .25, "1": last}
                if valid:
                    self.assertEqual(decode_prediction(value, questions)["answers"][2]["probabilities"]["hard"], last)
                else:
                    with self.assertRaises(BridgeError):
                        decode_prediction(value, questions)

    def test_unknown_fields_and_malformed_schema(self):
        values = []
        for target in ["root", "question", "option"]:
            value = request()
            obj = value if target == "root" else value["questions"][1] if target == "question" else value["questions"][1]["options"][0]
            obj["ignored"] = "SECRET_INPUT"; values.append(value)
        value = request(); value["questions"][1]["levels"] = []; values.append(value)
        value = request(); value["questions"][1]["options"][0]["id"] = "bad id"; values.append(value)
        value = request(); value["questions"][1]["options"][1]["id"] = "prioritize"; values.append(value)
        value = request(); value["questions"][0]["options"] = []; values.append(value)
        value = request(); value["state"] = {"data": "structured"}; values.append(value)
        for value in values:
            with self.subTest(value=value), self.assertRaises(BridgeError) as error:
                compile_request(value)
            self.assertNotIn("SECRET_INPUT", str(error.exception))

    def test_sizes_head_limits_and_unicode_bytes(self):
        values = []
        value = request(); value["state"] = "é" * 2049; values.append(value)
        value = request(); value["questions"][0]["instructions"] = "x" * 513; values.append(value)
        value = request(); value["questions"] = [dict(value["questions"][0], id="q" + str(i)) for i in range(17)]; values.append(value)
        value = request(); value["questions"][1]["options"] *= 17; values.append(value)
        value = request(); value["questions"][2]["levels"] = [{"id": "l" + str(i), "description": "x"} for i in range(17)]; values.append(value)
        value = request(); value["questions"] = [dict(value["questions"][1], id="q" + str(i), options=[{"id":"o"+str(j),"description":"x"} for j in range(32)]) for i in range(4)]; values.append(value)
        for value in values:
            with self.subTest(value=value), self.assertRaises(BridgeError):
                compile_request(value)
        with self.assertRaises(BridgeError):
            bridge.load_json(b" " * (bridge.MAX_REQUEST_BYTES + 1))

    def test_output_invalid_types_keys_and_values(self):
        values = []
        for invalid in [True, -1, 1.1, float("inf"), float("nan"), "0.8"]:
            value = prediction(); value["answers"]["usable"]["true"] = invalid; values.append(value)
        for invalid in [True, 0, "170", 1.2]:
            value = prediction(); value["tokens"] = invalid; values.append(value)
        for invalid in [True, -1, float("inf"), "1"]:
            value = prediction(); value["ms"] = invalid; values.append(value)
        value = prediction(); value["answers"]["extra"] = {}; values.append(value)
        value = prediction(); value["answers"]["usable"]["extra"] = 0; values.append(value)
        value = prediction(); value["answers"]["effort"] = {"0": 1}; values.append(value)
        for value in values:
            with self.subTest(value=value), self.assertRaises(BridgeError):
                decode_prediction(value, compile_request(request())[1])
        for data in [b'{"state":"one","state":"two"}', b'{"state":NaN}', b'\xff', b'{} {}']:
            with self.assertRaises(BridgeError):
                bridge.load_json(data)


class NativeBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.fixture = FakeArtifacts()
        self.addCleanup(self.fixture.close)

    def test_joint_native_request_stages_verified_symlinks_with_fresh_cache(self):
        stale = self.fixture.model / "compiled"; stale.mkdir(); (stale / "poison").write_text("unverified")
        for _ in range(2):
            result = self.fixture.runtime.infer(request())
            self.assertEqual(len(result["answers"]), 4)
            self.assertEqual(result["model"]["checker_sha256"], self.fixture.config.checker_sha256)
            probe = json.loads((self.fixture.root / "probe").read_text())
            self.assertNotEqual(probe["model"], str(self.fixture.model))
            self.assertEqual(len(probe["record"]["questions"]), 4)
            self.assertFalse(Path(probe["model"]).exists())
        self.assertEqual((stale / "poison").read_text(), "unverified")
        self.assertEqual(len((self.fixture.root / "calls").read_text().splitlines()), 2)

    def test_every_source_hash_and_checker_digest_is_verified_before_execution(self):
        for relative in list(self.fixture.hashes) + ["checker"]:
            with self.subTest(relative=relative):
                path = self.fixture.checker if relative == "checker" else self.fixture.model / relative
                original = path.read_bytes(); path.write_bytes(original + b"tamper")
                try:
                    with self.assertRaises(BridgeError) as error:
                        self.fixture.runtime.infer(request())
                    self.assertEqual(error.exception.code, "missing_artifact")
                    self.assertFalse((self.fixture.root / "started").exists())
                finally:
                    path.write_bytes(original)

    def test_provenance_does_not_replace_file_verification_and_is_bounded(self):
        self.fixture.provenance.write_text('{}')
        with self.assertRaises(BridgeError) as error:
            self.fixture.runtime.infer(request())
        self.assertEqual(error.exception.code, "missing_artifact")
        self.fixture.provenance.write_bytes(b"x" * 32769)
        with self.assertRaises(BridgeError):
            self.fixture.runtime.infer(request())

    def test_native_bad_large_stderr_and_exit_are_private_and_bounded_then_recover(self):
        for mode in ["bad", "huge", "stderr", "exit"]:
            with self.subTest(mode=mode):
                self.fixture.mode(mode); started = time.monotonic()
                with self.assertRaises(BridgeError) as error:
                    self.fixture.runtime.infer(request())
                self.assertLess(time.monotonic() - started, 3)
                self.assertNotIn("SECRET_INPUT", str(error.exception))
                self.assertEqual(list(self.fixture.scratch.glob("clef-request-*")), [])
        self.fixture.mode("normal")
        self.assertTrue(self.fixture.runtime.infer(request())["ok"])

    def test_real_timeout_kills_process_group_and_recovers(self):
        from dataclasses import replace
        self.fixture.runtime.close()
        self.fixture.runtime = ClefRuntime(replace(self.fixture.config, timeout_seconds=.4))
        self.fixture.mode("slow")
        with self.assertRaises(BridgeError) as error:
            self.fixture.runtime.infer(request())
        self.assertEqual(error.exception.code, "timeout")
        for filename in ["started", "descendant"]:
            pid = int((self.fixture.root / filename).read_text())
            deadline = time.monotonic() + 2
            while time.monotonic() < deadline:
                state = subprocess.run(["ps", "-o", "stat=", "-p", str(pid)], capture_output=True, text=True).stdout.strip()
                if not state or state.startswith("Z"):
                    break
                time.sleep(.02)
            self.assertTrue(not state or state.startswith("Z"), (pid, state))
        self.fixture.mode("normal")
        self.assertTrue(self.fixture.runtime.infer(request())["ok"])

    def test_nonregular_provenance_is_rejected_before_opening(self):
        from dataclasses import replace
        import threading
        fifo = self.fixture.root / "provenance.fifo"
        os.mkfifo(fifo)
        self.fixture.runtime.close()
        self.fixture.runtime = ClefRuntime(replace(self.fixture.config, provenance=fifo))
        # Unblock a defective implementation so the red test cannot hang.
        def unblock():
            time.sleep(.2)
            fd = os.open(fifo, os.O_RDWR | os.O_NONBLOCK)
            os.write(fd, b"{}")
            time.sleep(.05)
            os.close(fd)
        worker = threading.Thread(target=unblock)
        worker.start()
        started = time.monotonic()
        try:
            with self.assertRaises(BridgeError):
                self.fixture.runtime.infer(request())
            self.assertLess(time.monotonic() - started, .1)
        finally:
            worker.join(1)

    def test_cross_runtime_scratch_lock_prevents_second_native(self):
        import concurrent.futures
        second = ClefRuntime(self.fixture.config)
        self.addCleanup(second.close)
        self.fixture.mode("gate")
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
            future = executor.submit(self.fixture.runtime.infer, request())
            self.fixture.wait_started()
            try:
                with self.assertRaises(BridgeError) as error:
                    second.infer(request())
                self.assertEqual(error.exception.code, "busy")
            finally:
                (self.fixture.root / "release").write_text("release")
            self.assertTrue(future.result()["ok"])
        self.assertEqual(len((self.fixture.root / "calls").read_text().splitlines()), 1)

    def test_macos_minimum_is_checked_without_native_load(self):
        with mock.patch("clef_bridge.platform.mac_ver", return_value=("14.7", ("", "", ""), "")):
            self.assertFalse(self.fixture.runtime.health()["ready"])
            with self.assertRaises(BridgeError) as error:
                self.fixture.runtime.infer(request())
            self.assertEqual(error.exception.code, "unsupported_platform")
        self.assertFalse((self.fixture.root / "started").exists())

    def test_cli_incomplete_stdin_has_total_deadline(self):
        args = [sys.executable, str(Path(__file__).with_name("clef_bridge.py")),
                "--checker", str(self.fixture.checker), "--model-dir", str(self.fixture.model),
                "--provenance", str(self.fixture.provenance), "--scratch-dir", str(self.fixture.scratch),
                "--checker-sha256", self.fixture.config.checker_sha256,
                "--checker-source-revision", bridge.RUNTIME_REVISION, "--checker-build-id", "fixture",
                "--input-timeout", ".15"]
        process = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            process.stdin.write(b'{'); process.stdin.flush()
            process.wait(timeout=2)
            self.assertEqual(process.returncode, 1)
            self.assertEqual(json.loads(process.stdout.read())["error"]["code"], "timeout")
        finally:
            process.kill() if process.poll() is None else None
            process.wait(timeout=2)
            process.stdin.close(); process.stdout.close(); process.stderr.close()

    def test_unsupported_platform_and_health_do_not_launch_native(self):
        with mock.patch("clef_bridge.platform.system", return_value="Linux"):
            self.assertFalse(self.fixture.runtime.health()["ready"])
            with self.assertRaises(BridgeError) as error:
                self.fixture.runtime.infer(request())
            self.assertEqual(error.exception.code, "unsupported_platform")
        self.assertFalse((self.fixture.root / "started").exists())


if __name__ == "__main__":
    unittest.main()
