"""Only synthetic bytes/text; exercise the fixture server's real HTTP control/response path."""
import json
import struct
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

import caption_test_server as fixture


class SyntheticCaptionCasesTest(unittest.TestCase):
    def setUp(self):
        with fixture.lock:
            fixture.state.update(stt_calls=0, cleanup_calls=0, stt_completed=0, cleanup_completed=0, wav_valid=None, stt_delay_ms=0,
                                 cleanup_delay_ms=0, cleanup_fail=False, text_case="short")
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), fixture.Handler)
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join(2)

    def post(self, path, body):
        request = urllib.request.Request(self.base + path, data=body,
                                         headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=3) as response:
            return json.load(response)

    def test_long_and_rtl_controls_reach_both_inference_responses(self):
        pcm = bytes(64)
        wav = struct.pack("<4sI4s4sIHHIIHH4sI", b"RIFF", 36 + len(pcm), b"WAVE", b"fmt ",
                          16, 1, 1, 16000, 32000, 2, 16, b"data", len(pcm)) + pcm
        for name in ("long", "rtl"):
            self.post("/_test/control", json.dumps({"text_case": name}).encode())
            raw = self.post("/v1/audio/transcriptions", wav)["text"]
            cleaned = self.post("/v1/chat/completions", b"{}")["choices"][0]["message"]["content"]
            self.assertEqual(fixture.TEXT_CASES[name], (raw, cleaned))
            self.assertGreater(len(raw), 1000)
        self.assertTrue(fixture.state["wav_valid"])
        self.assertEqual(2, fixture.state["stt_calls"])
        self.assertEqual(2, fixture.state["cleanup_calls"])
        self.assertEqual(2, fixture.state["stt_completed"])
        self.assertEqual(2, fixture.state["cleanup_completed"])

    def test_unknown_text_case_is_rejected_without_mutating_state(self):
        with self.assertRaises(urllib.error.HTTPError) as failure:
            self.post("/_test/control", b'{"text_case":"arbitrary content","stt_delay_ms":7000}')
        self.assertEqual(400, failure.exception.code)
        self.assertEqual("short", fixture.state["text_case"])
        self.assertEqual(0, fixture.state["stt_delay_ms"])

    def test_old_delayed_response_cannot_change_counters_after_reset(self):
        pcm = bytes(64)
        wav = struct.pack("<4sI4s4sIHHIIHH4sI", b"RIFF", 36 + len(pcm), b"WAVE", b"fmt ",
                          16, 1, 1, 16000, 32000, 2, 16, b"data", len(pcm)) + pcm
        self.post("/_test/control", b'{"stt_delay_ms":600}')
        errors = []
        def delayed_request():
            try:
                self.post("/v1/audio/transcriptions", wav)
            except Exception as error:
                errors.append(type(error).__name__)
        caller = threading.Thread(target=delayed_request)
        caller.start()
        deadline = time.monotonic() + 2
        while fixture.state["stt_calls"] != 1 and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertEqual(1, fixture.state["stt_calls"])
        self.assertEqual(0, fixture.state["stt_completed"])
        self.post("/_test/control", b'{"reset":true,"stt_delay_ms":0}')
        caller.join(3)
        self.assertFalse(caller.is_alive())
        self.assertEqual([], errors)
        self.assertEqual(0, fixture.state["stt_calls"])
        self.assertEqual(0, fixture.state["stt_completed"])


if __name__ == "__main__":
    unittest.main()
