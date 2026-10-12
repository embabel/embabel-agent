#!/usr/bin/env python3
"""Embabel's experimental CLEF decision bridge (Apache-2.0).

Adapted from the isolated one-shot bridge. Native code/weights are external:
https://huggingface.co/FluidInference/clef-text-0.6b-coreml
https://github.com/FluidInference/FluidUse

Reads one JSON object on stdin, then EOF; emits one JSON result, exiting 0 for
ok:true or 1 for a sanitized typed failure. No installation or model load at
import. Required CLI paths and checker attestation are listed by --help.
The owned envelope has string state and a questions list with id, kind,
instructions and named options (choice/classification) or ordered levels
(rating). Proposition has implicit true/false. Each success answer preserves
all probabilities; confidence is selected pmax, rating expected_index is the
probability-weighted zero-based level index. This is not TypeSafe confidence.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import selectors
import signal
import stat
import subprocess
import sys
import tempfile
import threading
import time

try:
    import fcntl
except ImportError:
    fcntl = None

MAX_REQUEST_BYTES = 16_384
MAX_STATE_BYTES = 4_096
MAX_QUESTIONS = 16
MAX_OPTIONS = 96
MAX_STRING_BYTES = 512
TIMEOUT_SECONDS = 45
MAX_RESPONSE_BYTES = 65_536
MAX_NATIVE_STDOUT_BYTES = 32_768
MAX_NATIVE_STDERR_BYTES = 16_384
MAX_PROVENANCE_BYTES = 32_768
PROTOCOL = "embabel-clef-decisions-v1"
SOURCE_REPO = "FluidInference/clef-text-0.6b-coreml"
SOURCE_REVISION = "3c61a60954478ef385aa42d67d5764642dcc9135"
RUNTIME_REVISION = "0230049c4e81f8de53ae1086b20cd9106c511105"
VARIANT = "local-single-function-L1024"
DECODER_SPEC = "Decoder.mlpackage/Data/com.apple.CoreML/model.mlmodel"
HEAD_SPEC = "Head.mlpackage/Data/com.apple.CoreML/model.mlmodel"
DECODER_WEIGHTS = "Decoder.mlpackage/Data/com.apple.CoreML/weights/weight.bin"
HEAD_WEIGHTS = "Head.mlpackage/Data/com.apple.CoreML/weights/weight.bin"
PINNED_HASHES = {
    DECODER_SPEC: "49049d20da650ffa67b550ce0ac08f12aff305b4f9e6bbfe6dbc8e46408ab2e1",
    HEAD_SPEC: "71045bc150f3091142578e40bde13f7db5b3435d127af7f1c7dda6b637a31ec4",
    DECODER_WEIGHTS: "cbe7a6f22befa4edee09e7c806b2b0b9db8533d1ede737540fa82a0261435742",
    HEAD_WEIGHTS: "d028526027d6ce12313fbc80ad35f148ecb815f09f83695c5bb2b5872dbd4657",
    "config.json": "af356b87b840722b77068df83612d6ad6efdd19804c6c955e4689a0e10c6bf58",
    "tokenizer.json": "be75606093db2094d7cd20f3c2f385c212750648bd6ea4fb2bf507a6a4c55506",
    "embeddings.f16": "616f9374ddbaae3a033a0174557d502719154882e21e5d975ab5b58e0244459d",
}
ID = re.compile(r"^[A-Za-z][A-Za-z0-9_.-]{0,63}$")
ERROR_MESSAGES = {
    "invalid_request": "invalid owned decision request",
    "missing_artifact": "required experimental artifacts failed verification",
    "busy": "another CLEF inference is running",
    "timeout": "CLEF request exceeded its deadline",
    "inference_failed": "native checker failed",
    "invalid_output": "native checker returned invalid output",
    "input_too_long": "request filled the 1024-token bucket and may be truncated",
    "unsupported_platform": "experimental runtime requires Apple silicon macOS",
    "cancelled": "experimental runtime is closed",
    "bridge_failure": "unexpected local bridge error",
}


class BridgeError(Exception):
    def __init__(self, code: str, message: str = None):
        self.code = code
        self.message = message or ERROR_MESSAGES[code]
        super().__init__(self.message)

    def response(self):
        return {"ok": False, "error": {"code": self.code, "message": self.message}}


def _pairs(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("duplicate JSON key")
        value[key] = item
    return value


def _reject_constant(value):
    raise ValueError("nonfinite JSON")


def load_json(data: bytes, maximum: int = MAX_REQUEST_BYTES, code: str = "invalid_request"):
    if not data or len(data) > maximum:
        raise BridgeError(code)
    try:
        return json.loads(data.decode("utf-8"), object_pairs_hook=_pairs,
                          parse_constant=_reject_constant)
    except (ValueError, UnicodeError, RecursionError):
        raise BridgeError(code) from None


def encoded_json(value, maximum: int = MAX_RESPONSE_BYTES):
    try:
        data = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
    except (ValueError, TypeError, UnicodeError, RecursionError):
        raise BridgeError("invalid_output") from None
    if len(data) > maximum:
        raise BridgeError("invalid_output")
    return data


def _fields(value, required, allowed):
    if not isinstance(value, dict) or not required <= set(value) or not set(value) <= allowed:
        raise BridgeError("invalid_request", "request has missing or unknown fields")


def _string(value, field: str, maximum: int = MAX_STRING_BYTES):
    if not isinstance(value, str) or not value.strip():
        raise BridgeError("invalid_request", field + " must be a nonempty string")
    try:
        length = len(value.encode("utf-8"))
    except UnicodeError:
        raise BridgeError("invalid_request", field + " must be valid UTF-8") from None
    if length > maximum:
        raise BridgeError("invalid_request", field + " exceeds its UTF-8 byte limit")
    return value


def _id(value):
    if not isinstance(value, str) or not ID.fullmatch(value):
        raise BridgeError("invalid_request", "invalid question or option id")
    return value


def _options(value, maximum):
    if not isinstance(value, list) or not 2 <= len(value) <= maximum:
        raise BridgeError("invalid_request", "invalid number of options or levels")
    options, seen = [], set()
    for item in value:
        _fields(item, {"id", "description"}, {"id", "description"})
        identifier = _id(item["id"])
        if identifier in seen:
            raise BridgeError("invalid_request", "duplicate option id")
        seen.add(identifier)
        options.append({"id": identifier, "description": _string(item["description"], "description")})
    return options


def compile_request(value):
    _fields(value, {"state", "questions"}, {"state", "questions"})
    state = _string(value["state"], "state", MAX_STATE_BYTES)
    raw_questions = value["questions"]
    if not isinstance(raw_questions, list) or not 1 <= len(raw_questions) <= MAX_QUESTIONS:
        raise BridgeError("invalid_request", "invalid number of questions")
    questions, checker_questions, seen, total_options = [], [], set(), 0
    for raw in raw_questions:
        if not isinstance(raw, dict):
            raise BridgeError("invalid_request")
        kind = raw.get("kind")
        if kind not in ("proposition", "choice", "rating", "classification"):
            raise BridgeError("invalid_request", "unsupported question kind")
        required = {"id", "kind", "instructions"}
        if kind != "proposition":
            required.add("levels" if kind == "rating" else "options")
        _fields(raw, required, required)
        qid = _id(raw["id"])
        if qid in seen:
            raise BridgeError("invalid_request", "duplicate question id")
        seen.add(qid)
        instructions = _string(raw["instructions"], "instructions")
        if kind == "proposition":
            wire, options = {"type": "noul", "instructions": instructions}, [{"id": "true"}, {"id": "false"}]
        elif kind == "rating":
            options = _options(raw["levels"], 16)
            wire = {"type": "score", "instructions": instructions, "criteria": [o["description"] for o in options]}
        else:
            options = _options(raw["options"], 32)
            wire = {"type": "choice", "instructions": instructions, "criteria": {o["id"]: o["description"] for o in options}}
        total_options += len(options)
        if total_options > MAX_OPTIONS:
            raise BridgeError("invalid_request", "request exceeds head option limit")
        questions.append({"id": qid, "kind": kind, "options": options})
        checker_questions.append([qid, wire])
    return {"state": state, "questions": checker_questions}, questions


def _number(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return False
    try:
        return math.isfinite(value)
    except OverflowError:
        return False


def decode_prediction(raw, questions):
    if not isinstance(raw, dict) or not isinstance(raw.get("answers"), dict):
        raise BridgeError("invalid_output", "checker returned no answers object")
    if not set(raw) <= {"tokens", "ms", "answers", "ids_head"}:
        raise BridgeError("invalid_output")
    by_id = raw["answers"]
    if set(by_id) != {q["id"] for q in questions}:
        raise BridgeError("invalid_output", "checker answer ids differ from request")
    tokens = raw.get("tokens")
    if isinstance(tokens, bool) or not isinstance(tokens, int) or tokens < 1:
        raise BridgeError("invalid_output", "checker returned invalid token count")
    if tokens >= 1024:
        raise BridgeError("input_too_long")
    latency = raw.get("ms")
    if not _number(latency) or latency < 0:
        raise BridgeError("invalid_output", "checker returned invalid latency")
    answers = []
    for question in questions:
        qid, kind = question["id"], question["kind"]
        wire = by_id[qid]
        mapping = {str(i): option["id"] for i, option in enumerate(question["options"])} if kind == "rating" else {o["id"]: o["id"] for o in question["options"]}
        if not isinstance(wire, dict) or set(wire) != set(mapping):
            raise BridgeError("invalid_output", "checker option ids differ from request")
        if any(not _number(p) or not 0 <= p <= 1 for p in wire.values()):
            raise BridgeError("invalid_output", "checker has invalid probability")
        probabilities = {mapping[key]: float(wire[key]) for key in mapping}
        if abs(math.fsum(probabilities.values()) - 1) > 1e-6:
            raise BridgeError("invalid_output", "checker probabilities do not sum to one")
        selected = max(probabilities, key=probabilities.get)
        answer = {"id": qid, "kind": kind, "selected_id": selected, "confidence": probabilities[selected], "probabilities": probabilities}
        if kind == "rating":
            answer["expected_index"] = math.fsum(i * probabilities[o["id"]] for i, o in enumerate(question["options"]))
        answers.append(answer)
    return {"input_tokens": tokens, "inference_ms": float(latency), "answers": answers}


@dataclass(frozen=True)
class Config:
    checker: Path
    model_dir: Path
    provenance: Path
    scratch_dir: Path
    checker_sha256: str
    checker_source_revision: str
    checker_build_id: str
    timeout_seconds: float = TIMEOUT_SECONDS

    def __post_init__(self):
        for field in ("checker", "model_dir", "provenance", "scratch_dir"):
            object.__setattr__(self, field, Path(getattr(self, field)).resolve())
        if not re.fullmatch(r"[0-9a-f]{64}", self.checker_sha256) or self.checker_source_revision != RUNTIME_REVISION:
            raise ValueError("explicit pinned checker digest/source revision required")
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", self.checker_build_id):
            raise ValueError("checker build id must be a nonprivate identifier")
        if not _number(self.timeout_seconds) or not 0 < self.timeout_seconds <= TIMEOUT_SECONDS:
            raise ValueError("request deadline must be positive and at most 45 seconds")


def _platform_supported():
    if platform.system() != "Darwin" or platform.machine() != "arm64" or fcntl is None:
        return False
    try:
        return int(platform.mac_ver()[0].split(".")[0]) >= 15
    except ValueError:
        return False


def _deadline(deadline):
    if time.monotonic() >= deadline:
        raise BridgeError("timeout")


def _hash_file(path, deadline, cancelled=None):
    try:
        if not stat.S_ISREG(path.stat().st_mode):
            raise BridgeError("missing_artifact")
        digest = hashlib.sha256()
        with path.open("rb") as source:
            while True:
                if cancelled is not None and cancelled.is_set():
                    raise BridgeError("cancelled")
                _deadline(deadline)
                block = source.read(1024 * 1024)
                if not block:
                    break
                digest.update(block)
        return digest.hexdigest()
    except OSError:
        raise BridgeError("missing_artifact") from None


def _kill_group(process):
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(process.pid, sig)
        except ProcessLookupError:
            pass
        if sig == signal.SIGTERM:
            try:
                process.wait(timeout=.2)
            except subprocess.TimeoutExpired:
                pass
    process.wait(timeout=1)


class ClefRuntime:
    def __init__(self, config: Config):
        self.config = config
        self._inference = threading.Lock()
        self._child_lock = threading.Lock()
        self._child = None
        self._closed = threading.Event()

    def health(self):
        c = self.config
        ready = _platform_supported() and not self._closed.is_set() and c.checker.is_file() and c.provenance.is_file() and c.scratch_dir.is_dir() and all((c.model_dir / p).is_file() for p in PINNED_HASHES)
        return {"ok": True, "protocol": PROTOCOL, "experimental": True, "ready": ready,
                "assets_verified": False, "verification": "all seven inputs and checker verified before each inference",
                "source_revision": SOURCE_REVISION, "variant": VARIANT, "requested_compute": "cpu"}

    def close(self):
        self._closed.set()
        with self._child_lock:
            if self._child is not None:
                _kill_group(self._child)

    def _verify(self, deadline):
        c = self.config
        try:
            if not stat.S_ISREG(c.provenance.stat().st_mode):
                raise BridgeError("missing_artifact")
            with c.provenance.open("rb") as source:
                provenance = load_json(source.read(MAX_PROVENANCE_BYTES + 1), MAX_PROVENANCE_BYTES, "missing_artifact")
        except OSError:
            raise BridgeError("missing_artifact") from None
        if not isinstance(provenance, dict) or any(provenance.get(k) != v for k, v in {
                "source_repo": SOURCE_REPO, "source_revision": SOURCE_REVISION,
                "variant": VARIANT, "license": "Apache-2.0"}.items()):
            raise BridgeError("missing_artifact")
        try:
            for part, spec, weights in [("Decoder", DECODER_SPEC, DECODER_WEIGHTS), ("Head", HEAD_SPEC, HEAD_WEIGHTS)]:
                declared = provenance["files"][part]
                if declared["derived_spec_sha256"] != PINNED_HASHES[spec] or declared["weights_sha256"] != PINNED_HASHES[weights]:
                    raise BridgeError("missing_artifact")
        except (KeyError, TypeError):
            raise BridgeError("missing_artifact") from None
        verified = {}
        for relative, expected in PINNED_HASHES.items():
            path = (c.model_dir / relative).resolve()
            if _hash_file(path, deadline, self._closed) != expected:
                raise BridgeError("missing_artifact")
            verified[relative] = path
        if _hash_file(c.checker, deadline, self._closed) != c.checker_sha256 or not os.access(c.checker, os.X_OK):
            raise BridgeError("missing_artifact")
        return verified

    def _stage(self, directory, verified):
        model = directory / "model"
        model.mkdir()
        for relative, source in verified.items():
            target = model / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.symlink_to(source)
        for part in ("Decoder", "Head"):
            # Generate fresh package metadata; never consume external manifests/caches.
            spec_id = "00000000-0000-0000-0000-000000000001"
            weights_id = "00000000-0000-0000-0000-000000000002"
            entries = {spec_id: {"author": "com.apple.CoreML", "description": "CoreML Model Specification", "name": "model.mlmodel", "path": "com.apple.CoreML/model.mlmodel"},
                       weights_id: {"author": "com.apple.CoreML", "description": "CoreML Model Weights", "name": "weights", "path": "com.apple.CoreML/weights"}}
            (model / (part + ".mlpackage") / "Manifest.json").write_bytes(encoded_json({"fileFormatVersion": "1.0.0", "itemInfoEntries": entries, "rootModelIdentifier": spec_id}))
        return model

    def _run_checker(self, model, input_path, directory, deadline):
        temporary = directory / "tmp"
        temporary.mkdir()
        env = {"PATH": os.defpath, "TMPDIR": str(temporary), "CLEF_SINGLE_MAIN": "1", "CLEF_COMPUTE": "cpu"}
        _deadline(deadline)
        with self._child_lock:
            if self._closed.is_set():
                raise BridgeError("cancelled")
            try:
                process = subprocess.Popen([str(self.config.checker), str(model), str(input_path)],
                                           stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                           env=env, cwd=directory, start_new_session=True)
            except OSError:
                raise BridgeError("inference_failed") from None
            self._child = process
        output = bytearray()
        counts = {"stdout": 0, "stderr": 0}
        limits = {"stdout": MAX_NATIVE_STDOUT_BYTES, "stderr": MAX_NATIVE_STDERR_BYTES}
        try:
            with selectors.DefaultSelector() as selector:
                for stream, name in [(process.stdout, "stdout"), (process.stderr, "stderr")]:
                    os.set_blocking(stream.fileno(), False)
                    selector.register(stream, selectors.EVENT_READ, name)
                while selector.get_map():
                    if self._closed.is_set():
                        raise BridgeError("cancelled")
                    _deadline(deadline)
                    for key, _ in selector.select(min(.05, max(0, deadline - time.monotonic()))):
                        block = os.read(key.fd, min(65536, limits[key.data] - counts[key.data] + 1))
                        if not block:
                            selector.unregister(key.fileobj)
                            continue
                        counts[key.data] += len(block)
                        if counts[key.data] > limits[key.data]:
                            raise BridgeError("invalid_output")
                        if key.data == "stdout":
                            output.extend(block)
                while process.poll() is None:
                    if self._closed.wait(min(.02, max(0, deadline-time.monotonic()))):
                        raise BridgeError("cancelled")
                    _deadline(deadline)
                if self._closed.is_set():
                    raise BridgeError("cancelled")
                if process.returncode != 0:
                    raise BridgeError("inference_failed")
                lines = bytes(output).splitlines()
                if len(lines) != 1:
                    raise BridgeError("invalid_output")
                return load_json(lines[0], MAX_NATIVE_STDOUT_BYTES, "invalid_output")
        finally:
            with self._child_lock:
                _kill_group(process)
                self._child = None
            process.stdout.close()
            process.stderr.close()

    def infer(self, request):
        wire, questions = compile_request(request)
        if not _platform_supported():
            raise BridgeError("unsupported_platform")
        if self._closed.is_set():
            raise BridgeError("cancelled")
        if not self._inference.acquire(blocking=False):
            raise BridgeError("busy")
        started = time.monotonic()
        deadline = started + self.config.timeout_seconds
        try:
            try:
                lock = (self.config.scratch_dir / ".clef-native.lock").open("a")
            except OSError:
                raise BridgeError("missing_artifact") from None
            with lock:
                try:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    raise BridgeError("busy") from None
                verified = self._verify(deadline)
                with tempfile.TemporaryDirectory(dir=self.config.scratch_dir, prefix="clef-request-") as temporary:
                    directory = Path(temporary)
                    model = self._stage(directory, verified)
                    input_path = directory / "request.jsonl"
                    input_path.write_bytes(encoded_json(wire, MAX_NATIVE_STDOUT_BYTES) + b"\n")
                    raw = self._run_checker(model, input_path, directory, deadline)
                    decoded = decode_prediction(raw, questions)
            result = {"ok": True, "experimental": True, "model": {
                "source_repo": SOURCE_REPO, "source_revision": SOURCE_REVISION, "variant": VARIANT,
                "license": "Apache-2.0", "requested_compute": "cpu", "asset_sha256": dict(PINNED_HASHES),
                "checker_sha256": self.config.checker_sha256,
                "checker_source_revision": self.config.checker_source_revision,
                "checker_build_id": self.config.checker_build_id,
                "checker_provenance": "operator-supplied source/build; digest identifies bytes, not origin",
                "confidence_semantics": "selected_probability"},
                "wall_ms": (time.monotonic()-started)*1000, **decoded}
            encoded_json(result)
            return result
        except BridgeError:
            raise
        except Exception:
            raise BridgeError("bridge_failure") from None
        finally:
            self._inference.release()


def configuration_parser(description=__doc__):
    parser = argparse.ArgumentParser(description=description)
    for name in ("checker", "model-dir", "provenance", "scratch-dir"):
        parser.add_argument("--" + name, required=True, type=Path)
    for name in ("checker-sha256", "checker-source-revision", "checker-build-id"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--timeout-seconds", type=float, default=TIMEOUT_SECONDS)
    return parser


def config_from_args(args):
    return Config(args.checker, args.model_dir, args.provenance, args.scratch_dir,
                  args.checker_sha256, args.checker_source_revision, args.checker_build_id, args.timeout_seconds)


def _read_stdin(stream, timeout):
    if not _number(timeout) or not 0 < timeout <= 30:
        raise BridgeError("invalid_request")
    descriptor = stream.fileno()
    if stat.S_ISREG(os.fstat(descriptor).st_mode):
        return stream.read(MAX_REQUEST_BYTES + 1)
    deadline, data = time.monotonic() + timeout, bytearray()
    with selectors.DefaultSelector() as selector:
        selector.register(descriptor, selectors.EVENT_READ)
        while True:
            _deadline(deadline)
            if not selector.select(max(0, deadline-time.monotonic())):
                raise BridgeError("timeout")
            block = os.read(descriptor, min(4096, MAX_REQUEST_BYTES + 1 - len(data)))
            if not block:
                return bytes(data)
            data.extend(block)
            if len(data) > MAX_REQUEST_BYTES:
                raise BridgeError("invalid_request")


def main(argv=None):
    parser = configuration_parser()
    parser.add_argument("--input-timeout", type=float, default=5.0)
    args = parser.parse_args(argv)
    runtime = None
    try:
        request = load_json(_read_stdin(sys.stdin.buffer, args.input_timeout))
        compile_request(request)
        runtime = ClefRuntime(config_from_args(args))
        result, status = runtime.infer(request), 0
    except BridgeError as error:
        result, status = error.response(), 1
    except Exception:
        result, status = BridgeError("bridge_failure").response(), 1
    finally:
        if runtime is not None:
            runtime.close()
    sys.stdout.buffer.write(encoded_json(result) + b"\n")
    return status


if __name__ == "__main__":
    sys.exit(main())
