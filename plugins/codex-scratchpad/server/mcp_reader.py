#!/usr/bin/env python3
"""Codex MCP reader for the local Codex Scratchpad inbox.

This is deliberately self-contained: Codex runs it from the installed plugin
cache, while the persistent LAN bridge writes PNGs into ~/.codex-scratchpad.
"""
from __future__ import annotations

import base64
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

INBOX = Path.home() / ".codex-scratchpad" / "inbox"
INDEX = INBOX / "index.json"

TOOLS = [
    {"name": "scratchpad_latest", "description": "Retrieve the newest pending image pushed from Codex Scratchpad on the local Wi-Fi.", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "scratchpad_list", "description": "List the local Codex Scratchpad inbox.", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "scratchpad_acknowledge", "description": "Mark a scratchpad image as processed after it has been used.", "inputSchema": {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]}},
]


def read_index() -> list[dict[str, Any]]:
    try:
        return json.loads(INDEX.read_text())
    except (OSError, json.JSONDecodeError):
        return []


def write_index(items: list[dict[str, Any]]) -> None:
    INBOX.mkdir(parents=True, exist_ok=True)
    temporary = INDEX.with_suffix(".tmp")
    temporary.write_text(json.dumps(items, indent=2))
    temporary.replace(INDEX)


def reply(request_id: Any, result: dict[str, Any]) -> None:
    print(json.dumps({"jsonrpc": "2.0", "id": request_id, "result": result}), flush=True)


def main() -> None:
    for line in sys.stdin:
        try:
            request = json.loads(line)
            request_id, method = request.get("id"), request.get("method")
            if request_id is None:
                continue
            if method == "initialize":
                reply(request_id, {"protocolVersion": request.get("params", {}).get("protocolVersion", "2024-11-05"), "capabilities": {"tools": {}}, "serverInfo": {"name": "codex-scratchpad", "version": "0.1.0"}})
            elif method == "tools/list":
                reply(request_id, {"tools": TOOLS})
            elif method == "tools/call":
                params = request.get("params", {})
                name = params.get("name")
                items = read_index()
                if name == "scratchpad_latest":
                    pending = [item for item in items if item.get("state") == "pending"]
                    item = max(pending, key=lambda value: value.get("created_at", ""), default=None)
                    if not item:
                        reply(request_id, {"content": [{"type": "text", "text": "No pending scratchpad image."}]})
                    else:
                        image_path = (INBOX / item["file"]).resolve()
                        image = image_path.read_bytes()
                        metadata = {key: value for key, value in item.items() if key != "file"}
                        metadata["image_path"] = str(image_path)
                        reply(request_id, {"content": [{"type": "text", "text": json.dumps(metadata)}, {"type": "image", "data": base64.b64encode(image).decode(), "mimeType": "image/png"}]})
                elif name == "scratchpad_list":
                    reply(request_id, {"content": [{"type": "text", "text": json.dumps(sorted(items, key=lambda value: value.get("created_at", ""), reverse=True))}]})
                elif name == "scratchpad_acknowledge":
                    item_id = str(params.get("arguments", {}).get("id", ""))
                    found = False
                    for item in items:
                        if item.get("id") == item_id:
                            item["state"] = "processed"
                            item["processed_at"] = datetime.now(timezone.utc).isoformat()
                            found = True
                    if found:
                        write_index(items)
                    reply(request_id, {"content": [{"type": "text", "text": "Acknowledged" if found else "Unknown id"}]})
                else:
                    reply(request_id, {"content": [{"type": "text", "text": "Unknown tool"}], "isError": True})
            else:
                reply(request_id, {"error": {"code": -32601, "message": "Method not found"}})
        except Exception as error:
            reply(request.get("id") if "request" in locals() else None, {"error": {"code": -32603, "message": str(error)}})


if __name__ == "__main__":
    main()
