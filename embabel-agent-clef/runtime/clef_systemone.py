"""Local string subset of TypeSafe's SystemOne OpenAPI 0.2.0 (Apache-2.0).

Shape source: https://api.typesafe.ai/openapi.json (version 0.2.0).
This converter composes Embabel's proven experimental CLEF bridge. It does not
claim publisher HTTP support, vendor confidence/error/billing semantics, or
input equivalence after internal identifier canonicalization. Instructions,
state, rubric levels and descriptions are nonblank strings under native limits.
Public ids are preserved externally; canonical q001/o001/l001 retain order.

Choice confidence and score confidence remain the native selected probability;
score is the native expected level index, never a selected-level field. Usage
output_tokens=0 describes a local joint head with no text generation.
X-Embabel-Clef-Provenance is an optional vendor-external extension: flat original
verified model metadata plus protocol/experimental, ASCII JSON <=8192 bytes.
Catalog release_date names the pinned upstream artifact revision date, not a
release of the derived runtime. Neither conversion nor catalog loads a model.
"""
import json
import math
import re

import clef_bridge as bridge

MODEL_NAME = "clef-text-0.6b-coreml"
SYSTEMONE_PROTOCOL = "embabel-clef-systemone-v1"
PROVENANCE_HEADER = "X-Embabel-Clef-Provenance"
MAX_PROVENANCE_HEADER_BYTES = 8192
MODEL_FIELDS = {"source_repo", "source_revision", "variant", "license", "requested_compute",
                "asset_sha256", "checker_sha256", "checker_source_revision", "checker_build_id",
                "checker_provenance", "confidence_semantics"}


def model_catalog():
    return {"models": [{"name": MODEL_NAME, "release_date": "2026-10-10",
                       "description": "Experimental local derived L1024 CLEF CPU profile; date is the pinned upstream artifact revision date, not derived runtime support."}]}


def _request_budget(value):
    try:
        bridge.encoded_json(value, bridge.MAX_REQUEST_BYTES)
    except bridge.BridgeError:
        raise bridge.BridgeError("invalid_request") from None


def compile_systemone(value):
    """Return one owned joint request and ordered public-id response bindings."""
    _request_budget(value)
    bridge._fields(value, {"model", "state", "questions"}, {"model", "state", "questions"})
    if value["model"] != MODEL_NAME:
        raise bridge.BridgeError("invalid_request", "unsupported local model")
    public_questions = value["questions"]
    if not isinstance(public_questions, dict) or not 1 <= len(public_questions) <= bridge.MAX_QUESTIONS:
        raise bridge.BridgeError("invalid_request", "invalid number of questions")
    owned = {"state": value["state"], "questions": []}
    bindings = []
    for index, (public_id, question) in enumerate(public_questions.items(), 1):
        bridge._string(public_id, "question id", bridge.MAX_REQUEST_BYTES)
        if not isinstance(question, dict) or question.get("type") not in ("noul", "choice", "score"):
            raise bridge.BridgeError("invalid_request", "unsupported question type")
        kind = question["type"]
        required = {"type", "instructions"}
        if kind != "noul":
            required.add("criteria")
        bridge._fields(question, required, {"type", "instructions", "criteria"})
        internal_id = "q%03d" % index
        native = {"id": internal_id, "instructions": question["instructions"]}
        binding = {"id": internal_id, "public_id": public_id, "type": kind}
        if kind == "noul":
            if "criteria" in question and (not isinstance(question["criteria"], dict) or question["criteria"]):
                raise bridge.BridgeError("invalid_request", "custom noul criteria are unsupported")
            native["kind"] = "proposition"
        elif kind == "choice":
            criteria = question["criteria"]
            if not isinstance(criteria, dict) or not 2 <= len(criteria) <= 32:
                raise bridge.BridgeError("invalid_request", "invalid choice criteria")
            native["kind"], native["options"], binding["options"] = "choice", [], {}
            for option_index, (name, description) in enumerate(criteria.items(), 1):
                bridge._string(name, "choice id", bridge.MAX_REQUEST_BYTES)
                if description is not None and not isinstance(description, str):
                    raise bridge.BridgeError("invalid_request", "structured choice descriptions are unsupported")
                description = description if description is not None and description.strip() else name
                internal_option = "o%03d" % option_index
                native["options"].append({"id": internal_option, "description": description})
                binding["options"][internal_option] = name
        else:
            levels = question["criteria"]
            if not isinstance(levels, list) or not 2 <= len(levels) <= 16:
                raise bridge.BridgeError("invalid_request", "invalid ordered score criteria")
            native["kind"] = "rating"
            native["levels"] = [{"id": "l%03d" % i, "description": description} for i, description in enumerate(levels, 1)]
            binding["levels"] = list(levels)
        owned["questions"].append(native)
        bindings.append(binding)
    _request_budget(owned)
    bridge.compile_request(owned)
    return owned, bindings


def provenance_header(model):
    """Bound and identify the native metadata without attesting checker origin."""
    if not isinstance(model, dict) or set(model) != MODEL_FIELDS:
        raise bridge.BridgeError("invalid_output")
    identity = {"source_repo": bridge.SOURCE_REPO, "source_revision": bridge.SOURCE_REVISION,
                "variant": bridge.VARIANT, "license": "Apache-2.0", "requested_compute": "cpu",
                "checker_source_revision": bridge.RUNTIME_REVISION, "confidence_semantics": "selected_probability"}
    if any(model.get(key) != expected for key, expected in identity.items()) or model["asset_sha256"] != bridge.PINNED_HASHES:
        raise bridge.BridgeError("invalid_output")
    if not isinstance(model["checker_sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", model["checker_sha256"]):
        raise bridge.BridgeError("invalid_output")
    if not isinstance(model["checker_build_id"], str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", model["checker_build_id"]):
        raise bridge.BridgeError("invalid_output")
    if not isinstance(model["checker_provenance"], str) or not model["checker_provenance"].strip():
        raise bridge.BridgeError("invalid_output")
    try:
        header = json.dumps({**model, "protocol": SYSTEMONE_PROTOCOL, "experimental": True},
                            ensure_ascii=True, allow_nan=False, separators=(",", ":"))
    except (ValueError, TypeError, RecursionError):
        raise bridge.BridgeError("invalid_output") from None
    if len(header.encode("ascii")) > MAX_PROVENANCE_HEADER_BYTES:
        raise bridge.BridgeError("invalid_output")
    return header


def _answer_probabilities(answer, expected):
    probabilities = answer.get("probabilities")
    if not isinstance(probabilities, dict) or set(probabilities) != set(expected):
        raise bridge.BridgeError("invalid_output")
    if any(not bridge._number(p) or not 0 <= p <= 1 for p in probabilities.values()):
        raise bridge.BridgeError("invalid_output")
    if abs(math.fsum(probabilities.values())-1) > 1e-6:
        raise bridge.BridgeError("invalid_output")
    selected = answer.get("selected_id")
    confidence = answer.get("confidence")
    if not isinstance(selected, str) or selected not in probabilities or not bridge._number(confidence):
        raise bridge.BridgeError("invalid_output")
    if confidence != probabilities[selected] or confidence != max(probabilities.values()):
        raise bridge.BridgeError("invalid_output")
    return probabilities


def systemone_response(result, bindings):
    """Convert validated native evidence without renormalizing or inventing it."""
    if not isinstance(result, dict) or result.get("ok") is not True or result.get("experimental") is not True:
        raise bridge.BridgeError("invalid_output")
    header = provenance_header(result.get("model"))
    tokens = result.get("input_tokens")
    if isinstance(tokens, bool) or not isinstance(tokens, int) or not 1 <= tokens < 1024:
        raise bridge.BridgeError("invalid_output")
    native_answers = result.get("answers")
    if not isinstance(native_answers, list) or len(native_answers) != len(bindings):
        raise bridge.BridgeError("invalid_output")
    by_id = {}
    for answer in native_answers:
        if not isinstance(answer, dict) or not isinstance(answer.get("id"), str) or answer["id"] in by_id:
            raise bridge.BridgeError("invalid_output")
        by_id[answer["id"]] = answer
    if set(by_id) != {binding["id"] for binding in bindings}:
        raise bridge.BridgeError("invalid_output")
    answers = {}
    for binding in bindings:
        answer = by_id[binding["id"]]
        kind = binding["type"]
        if answer.get("kind") != {"noul": "proposition", "choice": "choice", "score": "rating"}[kind]:
            raise bridge.BridgeError("invalid_output")
        expected = ["true", "false"] if kind == "noul" else binding["options"] if kind == "choice" else ["l%03d" % i for i in range(1,len(binding["levels"])+1)]
        probabilities = _answer_probabilities(answer, expected)
        if kind == "noul":
            converted = {"type": "noul", "noul": probabilities["true"]}
        elif kind == "choice":
            converted = {"type": "choice", "choice": binding["options"][answer["selected_id"]],
                         "confidence": answer["confidence"], "probabilities": {public: probabilities[internal] for internal, public in binding["options"].items()}}
        else:
            score = answer.get("expected_index")
            expected_score = math.fsum(i*probabilities[internal] for i, internal in enumerate(expected))
            if not bridge._number(score) or score != expected_score:
                raise bridge.BridgeError("invalid_output")
            converted = {"type": "score", "score": score, "confidence": answer["confidence"],
                         "legend": {str(i): level for i, level in enumerate(binding["levels"])},
                         "probabilities": {str(i): probabilities[internal] for i, internal in enumerate(expected)}}
        answers[binding["public_id"]] = converted
    return {"model": MODEL_NAME, "answers": answers, "usage": {"input_tokens": tokens, "output_tokens": 0}}, header
