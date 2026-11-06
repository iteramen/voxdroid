"""Loopback-only handset test double. Never records audio, transcripts, tokens, or request headers."""
import argparse
import json
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TEXT_CASES = {
    "short": ("test server voice message", "Test server: readable voice message."),
    "long": ("\n\n".join(f"Synthetic caption paragraph {i}. This fixture checks scrolling and readable wrapping." for i in range(1, 81)),) * 2,
    "rtl": ("\n\n".join(f"זוהי כתובית בדיקה פרטית לקריאה ולגלילה. פסקה {i}." for i in range(1, 41)),) * 2,
}
state = {"stt_calls": 0, "cleanup_calls": 0, "stt_completed": 0, "cleanup_completed": 0, "wav_valid": None, "stt_delay_ms": 0, "cleanup_delay_ms": 0, "cleanup_fail": False, "text_case": "short"}
lock = threading.Lock()
generation = 0


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def answer(self, status, payload):
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        if self.path == "/_test/state":
            with lock:
                snapshot = dict(state)
            self.answer(200, snapshot)
        else:
            self.answer(200, {"test_server": True})

    def do_POST(self):
        global generation
        length = int(self.headers.get("Content-Length", "0"))
        if not 0 <= length <= 11 * 1024 * 1024:
            self.answer(413, {"error": "test request limit"})
            return
        body = self.rfile.read(length)
        if self.path == "/_test/control":
            if length > 1024:
                self.answer(400, {"error": "control limit"})
                return
            try:
                values = json.loads(body)
            except ValueError:
                self.answer(400, {"error": "invalid control"})
                return
            if not isinstance(values, dict) or not isinstance(values.get("text_case", "short"), str) or values.get("text_case", "short") not in TEXT_CASES:
                self.answer(400, {"error": "unknown synthetic text case"})
                return
            with lock:
                if "text_case" in values:
                    state["text_case"] = values["text_case"]
                for key in ("stt_delay_ms", "cleanup_delay_ms"):
                    if key in values:
                        state[key] = max(0, min(10000, int(values[key])))
                if "cleanup_fail" in values:
                    state["cleanup_fail"] = bool(values["cleanup_fail"])
                if values.get("reset"):
                    generation += 1
                    state.update(stt_calls=0, cleanup_calls=0, stt_completed=0, cleanup_completed=0, wav_valid=None)
            self.answer(200, {"ok": True})
            return
        if self.path.endswith("/audio/transcriptions"):
            start = body.find(b"RIFF")
            valid = False
            if start >= 0 and len(body) >= start + 44:
                fmt, channels, rate = struct.unpack_from("<HHI", body, start + 20)
                bits = struct.unpack_from("<H", body, start + 34)[0]
                size = struct.unpack_from("<I", body, start + 40)[0]
                valid = body[start + 8:start + 12] == b"WAVE" and fmt == 1 and channels == 1 and rate == 16000 and bits == 16
                valid = valid and 0 < size <= 300 * 16000 * 2 and len(body) >= start + 44 + size
            del body
            with lock:
                state["stt_calls"] += 1
                state["wav_valid"] = valid
                delay = state["stt_delay_ms"]
                raw = TEXT_CASES[state["text_case"]][0]
                request_generation = generation
            time.sleep(delay / 1000)
            with lock:
                if request_generation == generation:
                    state["stt_completed"] += 1
            if not valid:
                self.answer(422, {"error": "expected normalized WAV"})
            else:
                self.answer(200, {"text": raw, "segments": [{"no_speech_prob": 0.01}], "language_probabilities": {"en": 0.99}})
        elif self.path.endswith("/chat/completions"):
            del body
            with lock:
                state["cleanup_calls"] += 1
                delay, fail = state["cleanup_delay_ms"], state["cleanup_fail"]
                cleaned = TEXT_CASES[state["text_case"]][1]
                request_generation = generation
            time.sleep(delay / 1000)
            with lock:
                if request_generation == generation:
                    state["cleanup_completed"] += 1
            self.answer(503 if fail else 200, {"error": "test cleanup failure"} if fail else {"choices": [{"message": {"content": cleaned}}]})
        else:
            del body
            self.answer(404, {"error": "unknown test route"})


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18881)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Caption test server listening on loopback port {args.port}; no payload logging", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
