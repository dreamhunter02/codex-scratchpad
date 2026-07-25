#!/usr/bin/env python3
"""Local-only bridge: Android POST inbox + minimal stdio MCP server.

The HTTP endpoint intentionally stays dependency-free so this can be started by
Codex or from a terminal without a cloud account.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import subprocess
import sys
import threading
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class Inbox:
    def __init__(self, root: Path) -> None:
        self.root = root.expanduser()
        self.root.mkdir(parents=True, exist_ok=True)
        self.index = self.root / "index.json"
        if not self.index.exists():
            self._write([])

    def _read(self) -> list[dict[str, Any]]:
        try:
            return json.loads(self.index.read_text())
        except (OSError, json.JSONDecodeError):
            return []

    def _write(self, items: list[dict[str, Any]]) -> None:
        temporary = self.index.with_suffix(".tmp")
        temporary.write_text(json.dumps(items, indent=2))
        temporary.replace(self.index)

    def add(self, item_id: str, caption: str, image: bytes) -> dict[str, Any]:
        safe_id = "".join(character for character in item_id if character.isalnum() or character in "-_")[:96]
        if not safe_id:
            raise ValueError("Invalid scribble id")
        filename = f"{safe_id}.png"
        temporary = self.root / f".{filename}.tmp"
        temporary.write_bytes(image)
        temporary.replace(self.root / filename)
        item = {"id": safe_id, "caption": caption[:500], "file": filename, "state": "pending", "created_at": utc_now()}
        items = self._read()
        items = [existing for existing in items if existing["id"] != safe_id]
        items.append(item)
        self._write(items)
        return item

    def latest(self) -> dict[str, Any] | None:
        pending = [item for item in self._read() if item.get("state") == "pending"]
        return max(pending, key=lambda item: item.get("created_at", ""), default=None)

    def list(self) -> list[dict[str, Any]]:
        return sorted(self._read(), key=lambda item: item.get("created_at", ""), reverse=True)

    def acknowledge(self, item_id: str) -> bool:
        items = self._read()
        found = False
        for item in items:
            if item.get("id") == item_id:
                item["state"] = "processed"
                item["processed_at"] = utc_now()
                found = True
        if found:
            self._write(items)
        return found


def handler_for(inbox: Inbox, token: str | None):
    class Handler(BaseHTTPRequestHandler):
        def _json(self, status: int, payload: dict[str, Any]) -> None:
            encoded = json.dumps(payload).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

        def do_GET(self) -> None:  # noqa: N802
            if self.path == "/health":
                self._json(HTTPStatus.OK, {"ok": True, "service": "codex-scratchpad"})
            else:
                self._json(HTTPStatus.NOT_FOUND, {"error": "not found"})

        def do_POST(self) -> None:  # noqa: N802
            if self.path != "/v1/scribbles":
                self._json(HTTPStatus.NOT_FOUND, {"error": "not found"})
                return
            if token and self.headers.get("X-Scratchpad-Token") != token:
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "invalid pairing token"})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 12_000_000:
                    raise ValueError("payload must be between 1 byte and 12 MB")
                payload = json.loads(self.rfile.read(length))
                image = base64.b64decode(payload["png_base64"], validate=True)
                if not image.startswith(b"\x89PNG\r\n\x1a\n"):
                    raise ValueError("only PNG images are accepted")
                item = inbox.add(str(payload["id"]), str(payload.get("caption", "")), image)
                self._json(HTTPStatus.CREATED, {"ok": True, "scribble": item})
            except (KeyError, ValueError, json.JSONDecodeError, base64.binascii.Error) as error:
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(error)})

        def log_message(self, *_: object) -> None:
            return
    return Handler


def start_http(inbox: Inbox, host: str, port: int, token: str | None) -> ThreadingHTTPServer:
    server = ThreadingHTTPServer((host, port), handler_for(inbox, token))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def start_bonjour_advertisement(port: int) -> subprocess.Popen[bytes] | None:
    """Advertise the bridge to Android NSD without adding a Python dependency."""
    command = "/usr/bin/dns-sd"
    if not os.path.exists(command):
        return None
    return subprocess.Popen(
        [command, "-R", "Codex Scratchpad", "_codex-scratchpad._tcp", "local.", str(port)],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


TOOLS = [
    {"name": "scratchpad_latest", "description": "Retrieve the newest pending image pushed from Codex Scratchpad on the local Wi-Fi.", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "scratchpad_list", "description": "List the local Codex Scratchpad inbox.", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "scratchpad_acknowledge", "description": "Mark a scratchpad image as processed after it has been used.", "inputSchema": {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]}},
]


def respond(message_id: Any, result: dict[str, Any]) -> None:
    print(json.dumps({"jsonrpc": "2.0", "id": message_id, "result": result}), flush=True)


def run_mcp(inbox: Inbox) -> None:
    for line in sys.stdin:
        try:
            request = json.loads(line)
            method, request_id = request.get("method"), request.get("id")
            if request_id is None:
                continue
            if method == "initialize":
                respond(request_id, {"protocolVersion": request.get("params", {}).get("protocolVersion", "2024-11-05"), "capabilities": {"tools": {}}, "serverInfo": {"name": "codex-scratchpad", "version": "0.1.0"}})
            elif method == "tools/list":
                respond(request_id, {"tools": TOOLS})
            elif method == "tools/call":
                params = request.get("params", {})
                name = params.get("name")
                if name == "scratchpad_latest":
                    item = inbox.latest()
                    if not item:
                        respond(request_id, {"content": [{"type": "text", "text": "No pending scratchpad image."}]})
                    else:
                        image = (inbox.root / item["file"]).read_bytes()
                        respond(request_id, {"content": [{"type": "text", "text": json.dumps({key: value for key, value in item.items() if key != "file"})}, {"type": "image", "data": base64.b64encode(image).decode(), "mimeType": "image/png"}]})
                elif name == "scratchpad_list":
                    respond(request_id, {"content": [{"type": "text", "text": json.dumps(inbox.list())}]})
                elif name == "scratchpad_acknowledge":
                    item_id = str(params.get("arguments", {}).get("id", ""))
                    respond(request_id, {"content": [{"type": "text", "text": "Acknowledged" if inbox.acknowledge(item_id) else "Unknown id"}]})
                else:
                    respond(request_id, {"content": [{"type": "text", "text": "Unknown tool"}], "isError": True})
            else:
                respond(request_id, {"error": {"code": -32601, "message": "Method not found"}})
        except Exception as error:  # Keep the stdio transport alive for the next tool call.
            respond(request.get("id") if "request" in locals() else None, {"error": {"code": -32603, "message": str(error)}})


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--inbox", default=os.environ.get("CODEX_SCRATCHPAD_INBOX", "~/.codex-scratchpad/inbox"))
    parser.add_argument("--token", default=os.environ.get("CODEX_SCRATCHPAD_TOKEN"))
    parser.add_argument("--http-only", action="store_true", help="Run the LAN bridge without the stdio MCP transport.")
    args = parser.parse_args()
    inbox = Inbox(Path(args.inbox))
    server = start_http(inbox, args.host, args.port, args.token)
    advertisement = start_bonjour_advertisement(args.port)
    try:
        if args.http_only:
            threading.Event().wait()
        else:
            run_mcp(inbox)
    finally:
        server.shutdown()
        if advertisement:
            advertisement.terminate()


if __name__ == "__main__":
    main()
