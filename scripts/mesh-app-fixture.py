#!/usr/bin/env python3
"""Synthetic Codeg + EasyTier peer for the opt-in Android integration test.

Requires easytier-core on PATH. No user servers, tokens, or network identities
are read. The target address exists only inside this fixture's mesh subnet map.
Keep this running while the documented instrumentation test runs; Ctrl-C stops
only the fixture's own peer and HTTP server.
"""
import argparse
import base64
import hashlib
import http.server
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import struct
import subprocess
import threading
import time

NETWORK = "codeg-app-fixture"
PASSWORD = "codeg-fixture-network-password"
TOKEN = "codeg-fixture-token"
COUNTS = {"health": 0, "folders": 0, "conversations": 0, "websocket": 0, "pong": 0}
LOCK = threading.Lock()
OUTPUT = None


def count(name):
    with LOCK:
        COUNTS[name] += 1
        (OUTPUT / "requests.json").write_text(json.dumps(COUNTS, indent=2) + "\n")


class Fixture(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if self.headers.get("Authorization") != "Bearer " + TOKEN:
            self.send_error(401)
            return
        if self.path == "/api/health":
            count("health")
            result = {"status": "ok", "version": "codeg-mesh-fixture"}
        elif self.path == "/api/list_all_folder_details":
            count("folders")
            result = [{"id": 1, "name": "fixture-project", "path": "/synthetic/fixture"}]
        elif self.path == "/api/list_all_conversations":
            count("conversations")
            result = []
        else:
            self.send_error(404)
            return
        body = json.dumps(result).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        expected = "codeg-token." + base64.urlsafe_b64encode(TOKEN.encode()).decode().rstrip("=")
        if self.path != "/ws/events" or expected not in self.headers.get("Sec-WebSocket-Protocol", ""):
            self.send_error(401)
            return
        accept = base64.b64encode(hashlib.sha1((self.headers["Sec-WebSocket-Key"] +
            "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.send_header("Sec-WebSocket-Protocol", "codeg-events")
        self.end_headers()
        self.close_connection = True
        count("websocket")
        self.frame(b'{"channel":"__ready__","payload":{}}')
        try:
            while True:
                head = self.rfile.read(2)
                if len(head) != 2:
                    return
                length = head[1] & 127
                if length == 126:
                    length = struct.unpack("!H", self.rfile.read(2))[0]
                elif length == 127:
                    length = struct.unpack("!Q", self.rfile.read(8))[0]
                if length > 1024 * 1024:
                    return
                mask = self.rfile.read(4) if head[1] & 128 else b""
                body = self.rfile.read(length)
                if mask:
                    body = bytes(value ^ mask[index % 4] for index, value in enumerate(body))
                if head[0] & 15 == 8:
                    self.frame(body, 0x88)
                    return
                if head[0] & 15 == 9:
                    self.frame(body, 0x8A)
                elif json.loads(body).get("action") == "ping":
                    self.frame(b'{"type":"pong"}')
                    count("pong")
        except (OSError, ValueError):
            return

    def frame(self, body, opcode=0x81):
        header = bytes([opcode, len(body)]) if len(body) < 126 else bytes([opcode, 126]) + struct.pack("!H", len(body))
        self.wfile.write(header + body)
        self.wfile.flush()


def freeport():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def main():
    global OUTPUT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("build/mesh-fixture"))
    parser.add_argument("--easytier-core", default=shutil.which("easytier-core"))
    args = parser.parse_args()
    if not args.easytier_core:
        parser.error("install easytier-core or pass --easytier-core")
    OUTPUT = args.output.resolve()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    peerport = freeport()
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
    httpport = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    config = f'''instance_name = "codeg-app-fixture-server"
ipv4 = "10.225.31.1"
dhcp = false
listeners = ["tcp://127.0.0.1:{peerport}"]
[network_identity]
network_name = "{NETWORK}"
network_secret = "{PASSWORD}"
[flags]
no_tun = true
use_smoltcp = true
private_mode = true
bind_device = false
[[proxy_network]]
cidr = "127.0.0.1/32"
mapped_cidr = "10.226.31.1/32"
'''
    path = OUTPUT / "peer.toml"
    path.write_text(config)
    environment = {key: value for key, value in os.environ.items() if not key.startswith("ET_")}
    with (OUTPUT / "peer.log").open("w") as log:
        peer = subprocess.Popen([args.easytier_core, "-c", str(path), "--console-log-level", "error", "--disable-p2p", "true"],
            stdout=log, stderr=log, env=environment)
        info = {"meshPeer": f"tcp://10.0.2.2:{peerport}", "meshServer": f"http://10.226.31.1:{httpport}",
                "meshNetwork": NETWORK, "peer_pid": peer.pid}
        (OUTPUT / "connection.json").write_text(json.dumps(info, indent=2) + "\n")
        print(json.dumps(info), flush=True)
        stopped = threading.Event()
        signal.signal(signal.SIGTERM, lambda *_: stopped.set())
        signal.signal(signal.SIGINT, lambda *_: stopped.set())
        try:
            while not stopped.wait(0.5):
                if peer.poll() is not None:
                    raise RuntimeError("Fixture peer exited before acceptance completed")
        finally:
            if peer.poll() is None:
                peer.terminate()
                try:
                    peer.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    peer.kill()
                    peer.wait()
            server.shutdown()


if __name__ == "__main__":
    main()
