"""Tests for the local SystemOne 0.2.0 string subset; no real model loads."""
import copy
import http.client
import json
from pathlib import Path
import unittest
from unittest import mock

import clef_bridge as bridge
import clef_systemone as profile
import test_clef_server as host_tests
from test_clef_bridge import request as owned_fixture


def standard_request():
    return {"model": profile.MODEL_NAME, "state": "A sourced museum overlaps the dates.", "questions": {
        "public yes/no?": {"type": "noul", "instructions": "Usable?"},
        "Which queue / équipe": {"type": "choice", "instructions": "Choose a queue", "criteria": {
            "Billing queue / crédit": None, "Research queue": "Evidence missing"}},
        "Effort (ordered)": {"type": "score", "instructions": "Rate effort", "criteria": ["Easy", "Hard"]}}}


class ProfileTests(unittest.TestCase):
    def setUp(self):
        self.host = host_tests.ServerTests(methodName="runTest")
        self.host.setUp()
        self.addCleanup(self.host.doCleanups)
        self.fixture = self.host.fixture

    def http(self, value=None, path="/v1/systemone", method="POST", raw=None):
        connection = http.client.HTTPConnection(*self.host.server.server_address, timeout=4)
        try:
            body = raw if raw is not None else json.dumps(value if value is not None else standard_request()).encode() if method=="POST" else None
            connection.request(method, path, body=body, headers={"Content-Type":"application/json"})
            response = connection.getresponse()
            return response.status, json.loads(response.read()), response.getheaders()
        finally:
            connection.close()

    def test_joint_real_socket_roundtrip_preserves_public_ids_and_flat_provenance(self):
        status, value, headers = self.http()
        self.assertEqual(status, 200)
        self.assertEqual(set(value), {"model","answers","usage"})
        self.assertEqual(value["model"], profile.MODEL_NAME)
        self.assertEqual(list(value["answers"]), list(standard_request()["questions"]))
        answers = list(value["answers"].values())
        self.assertEqual(answers[0], {"type":"noul","noul":.8})
        self.assertEqual(answers[1], {"type":"choice","choice":"Billing queue / crédit","confidence":.5,
                                    "probabilities":{"Billing queue / crédit":.5,"Research queue":.5}})
        self.assertEqual(answers[2], {"type":"score","score":.5,"confidence":.5,
                                    "legend":{"0":"Easy","1":"Hard"},"probabilities":{"0":.5,"1":.5}})
        self.assertEqual(value["usage"], {"input_tokens":170,"output_tokens":0})
        self.assertEqual(len((self.fixture.root/"calls").read_text().splitlines()), 1)
        native = json.loads((self.fixture.root/"probe").read_text())["record"]
        self.assertEqual([q[0] for q in native["questions"]], ["q001","q002","q003"])
        self.assertEqual(native["questions"][1][1]["criteria"], {"o001":"Billing queue / crédit","o002":"Evidence missing"})
        metadata_headers = [v for k,v in headers if k.lower()==profile.PROVENANCE_HEADER.lower()]
        self.assertEqual(len(metadata_headers), 1)
        self.assertLessEqual(len(metadata_headers[0].encode("ascii")), 8192)
        metadata = json.loads(metadata_headers[0])
        self.assertEqual(metadata["protocol"], profile.SYSTEMONE_PROTOCOL)
        self.assertIs(metadata["experimental"], True)
        self.assertEqual(metadata["source_revision"], bridge.SOURCE_REVISION)
        self.assertEqual(metadata["variant"], bridge.VARIANT)
        self.assertEqual(metadata["asset_sha256"], bridge.PINNED_HASHES)
        self.assertNotIn("model", metadata)
        self.assertEqual(metadata["confidence_semantics"], "selected_probability")

    def test_catalog_and_health_do_not_infer(self):
        with mock.patch.object(self.fixture.runtime, "infer", side_effect=AssertionError("no inference")):
            status, catalog, headers = self.http(path="/v1/models", method="GET")
            self.assertEqual(status, 200)
            self.assertEqual(set(catalog), {"models"})
            self.assertEqual(len(catalog["models"]), 1)
            model = catalog["models"][0]
            self.assertEqual(set(model), {"name","description","release_date"})
            self.assertEqual(model["name"], profile.MODEL_NAME)
            self.assertEqual(model["release_date"], "2026-10-10")
            self.assertIn("artifact revision", model["description"])
            self.assertEqual(self.http(path="/health", method="GET")[0], 200)
        self.assertFalse((self.fixture.root/"started").exists())
        self.assertFalse(any(k.lower()==profile.PROVENANCE_HEADER.lower() for k,v in headers))

    def test_mapping_more_than_nine_fields_options_and_levels_keeps_order(self):
        value={"model":profile.MODEL_NAME,"state":"input","questions":{
            "Question #"+str(i):{"type":"noul","instructions":"Is it present?"} for i in range(10)}}
        value["questions"]["Choice with twelve options"]={"type":"choice","instructions":"Pick","criteria":{
            "public "+str(i):str(i) for i in range(12)}}
        value["questions"]["Ordered twelve levels"]={"type":"score","instructions":"Rate","criteria":["level "+str(i) for i in range(12)]}
        owned, bindings = profile.compile_systemone(value)
        self.assertEqual([q["id"] for q in owned["questions"]], ["q"+str(i).zfill(3) for i in range(1,13)])
        self.assertEqual([o["id"] for o in owned["questions"][10]["options"]], ["o"+str(i).zfill(3) for i in range(1,13)])
        self.assertEqual([o["id"] for o in owned["questions"][11]["levels"]], ["l"+str(i).zfill(3) for i in range(1,13)])
        status, result, _ = self.http(value)
        self.assertEqual(status, 200)
        score = result["answers"]["Ordered twelve levels"]
        self.assertEqual(list(score["legend"]), [str(i) for i in range(12)])
        self.assertEqual(list(score["probabilities"]), [str(i) for i in range(12)])
        self.assertEqual(list(result["answers"]["Choice with twelve options"]["probabilities"]), list(value["questions"]["Choice with twelve options"]["criteria"]))
        self.assertAlmostEqual(score["score"], 5.5)
        self.assertAlmostEqual(score["confidence"], 1/12)
        self.assertNotIn("selected_id", score)

    def test_blank_choice_description_falls_back_to_public_name_and_empty_noul_criteria(self):
        for blank in [None, "", "   "]:
            value=standard_request()
            value["questions"]["public yes/no?"]["criteria"]={}
            value["questions"]["Which queue / équipe"]["criteria"]["Billing queue / crédit"]=blank
            owned,_=profile.compile_systemone(value)
            self.assertEqual(owned["questions"][1]["options"][0]["description"], "Billing queue / crédit")
            self.assertEqual(self.http(value)[0], 200)

    def test_nonuniform_score_is_expected_index_and_native_confidence_without_repair(self):
        owned, bindings=profile.compile_systemone(standard_request())
        native=self.fixture.runtime.infer(owned)
        raw={"tokens":321,"ms":2,"answers":{"q001":{"true":.6,"false":.4},
             "q002":{"o001":.25,"o002":.7499995},"q003":{"0":.25,"1":.75}}}
        native.update(bridge.decode_prediction(raw, bridge.compile_request(owned)[1]))
        result, header=profile.systemone_response(native, bindings)
        choice=result["answers"]["Which queue / équipe"]
        self.assertEqual(choice["probabilities"]["Research queue"], .7499995)
        self.assertEqual(choice["confidence"], .7499995)
        score=result["answers"]["Effort (ordered)"]
        self.assertEqual(score["score"], .75)
        self.assertEqual(score["confidence"], .75)
        self.assertEqual(set(score), {"type","score","legend","probabilities","confidence"})
        self.assertEqual(result["usage"]["input_tokens"], 321)
        for bad in [float("nan"), float("inf"), -.1, True, "0.25"]:
            tampered=copy.deepcopy(native); tampered["answers"][1]["probabilities"]["o001"]=bad
            with self.assertRaises(bridge.BridgeError):
                profile.systemone_response(tampered, bindings)
        tampered=copy.deepcopy(native); tampered["answers"][1]["probabilities"]["o002"]=.749998
        with self.assertRaises(bridge.BridgeError):
            profile.systemone_response(tampered, bindings)

    def test_invalid_subset_requests_fail_before_inference(self):
        values=[]
        for state in [None, {}, [], "", " ", "é"*2049]:
            value=standard_request();value["state"]=state;values.append(value)
        for instruction in [None, "", " ", {}, [], "x"*513]:
            value=standard_request();value["questions"]["public yes/no?"]["instructions"]=instruction;values.append(value)
        value=standard_request();del value["questions"]["public yes/no?"]["instructions"];values.append(value)
        for criteria in [None, [], {"true":"Customized yes"}, {"unknown":None}]:
            value=standard_request();value["questions"]["public yes/no?"]["criteria"]=criteria;values.append(value)
        for criteria in [{}, [], {"a":{}}, {"a":[],"b":"ok"}, {"   ":"blank id","b":"ok"}]:
            value=standard_request();value["questions"]["Which queue / équipe"]["criteria"]=criteria;values.append(value)
        for levels in [[], ["only one"], ["ok",None], ["ok",{}], ["ok"," "], ["x"]*17]:
            value=standard_request();value["questions"]["Effort (ordered)"]["criteria"]=levels;values.append(value)
        value=standard_request();value["model"]="jev-latest";values.append(value)
        value=standard_request();value["unknown"]="PRIVATE_INPUT";values.append(value)
        value=standard_request();value["questions"]["public yes/no?"]["unknown"]=True;values.append(value)
        value=standard_request();value["questions"]["public yes/no?"]["type"]="classification";values.append(value)
        value=standard_request();value["questions"]={" ":{"type":"noul","instructions":"ok"}};values.append(value)
        value=standard_request();value["questions"]={str(i):{"type":"noul","instructions":"ok"} for i in range(17)};values.append(value)
        with mock.patch.object(self.fixture.runtime, "infer", side_effect=AssertionError("validation must precede native")):
            for value in values:
                with self.subTest(value=value):
                    status,result,headers=self.http(value)
                    self.assertEqual(status,400)
                    self.assertEqual(result["error"]["code"],"invalid_request")
                    self.assertNotIn("PRIVATE_INPUT",json.dumps(result))
            for raw in [b'{"model":"one","model":"two"}',b'{"model":NaN}',b'x'*16385]:
                self.assertEqual(self.http(raw=raw)[0],400)
        self.assertFalse((self.fixture.root/"started").exists())

    def test_unicode_long_public_ids_preserved_and_fallback_still_obeys_native_string_limit(self):
        value=standard_request(); q=value["questions"].pop("Which queue / équipe")
        public="é question / "*100; q["criteria"]={"long "+"ß"*300:"valid description","other":"second"}
        value["questions"][public]=q
        status,result,_=self.http(value)
        self.assertEqual(status,200)
        self.assertIn(public,result["answers"])
        self.assertEqual(list(result["answers"][public]["probabilities"]),list(q["criteria"]))
        q["criteria"]["long "+"ß"*300]=None
        with mock.patch.object(self.fixture.runtime,"infer",side_effect=AssertionError("no native")):
            self.assertEqual(self.http(value)[0],400)

    def test_header_ascii_size_identity_and_error_status_boundaries(self):
        owned,bindings=profile.compile_systemone(standard_request())
        native=self.fixture.runtime.infer(owned)
        model=copy.deepcopy(native["model"]);model["checker_provenance"]="é\r\nworker"
        header=profile.provenance_header(model)
        self.assertTrue(header.isascii())
        self.assertNotIn("\r",header);self.assertNotIn("\n",header)
        self.assertEqual(json.loads(header)["checker_provenance"],"é\r\nworker")
        for field,value in [("checker_provenance","é"*9000),("source_revision","wrong"),("variant","official"),("confidence_semantics","entropy")]:
            tampered=copy.deepcopy(native);tampered["model"][field]=value
            with mock.patch.object(self.fixture.runtime,"infer",return_value=tampered):
                status,result,headers=self.http()
            self.assertEqual(status,502)
            self.assertFalse(any(k.lower()==profile.PROVENANCE_HEADER.lower() for k,v in headers))
        for code,status in [("busy",409),("timeout",504),("input_too_long",400),("missing_artifact",503),("invalid_output",502)]:
            with mock.patch.object(self.fixture.runtime,"infer",side_effect=bridge.BridgeError(code)):
                actual,result,headers=self.http()
            self.assertEqual(actual,status)
            self.assertEqual(result["error"]["code"],code)
            self.assertFalse(any(k.lower()==profile.PROVENANCE_HEADER.lower() for k,v in headers))

    def test_owned_endpoint_is_unchanged(self):
        status,result,headers=self.http(owned_fixture(),path="/v1/decisions")
        self.assertEqual(status,200)
        self.assertTrue(result["ok"])
        self.assertTrue(result["experimental"])
        self.assertEqual([a["kind"] for a in result["answers"]],["proposition","choice","rating","classification"])
        self.assertFalse(any(k.lower()==profile.PROVENANCE_HEADER.lower() for k,v in headers))


if __name__ == "__main__":
    unittest.main()
