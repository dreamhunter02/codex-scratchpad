# Codex Scratchpad

[![Android](https://img.shields.io/badge/Android-26%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Codex](https://img.shields.io/badge/Codex-MCP-FF8A1E?logo=openai&logoColor=white)](https://developers.openai.com/)
[![License](https://img.shields.io/badge/License-MIT-181717.svg)](LICENSE)
[![GitHub stars](https://img.shields.io/github/stars/dreamhunter02/codex-scratchpad?style=social)](https://github.com/dreamhunter02/codex-scratchpad/stargazers)

**Your phone is a visual input surface for Codex.** Draw with a finger, S Pen, Apple Pencil, or any stylus; tap **Push to Codex**; ask your local Codex agent to retrieve the image.

![Codex Scratchpad mobile concept](assets/mobile-concept.png)

## What it does

```text
phone / tablet ── same Wi-Fi ──> local bridge on your Mac ──MCP──> Codex
     draw + caption                 private PNG inbox              agent context
```

- Native Android canvas with finger + stylus pressure support
- Mobile and tablet responsive layout
- One-tap PNG push to your Mac over local Wi-Fi
- Dependency-free Python bridge + MCP server
- No Firebase, account, cloud sync, or external image upload
- Image and optional caption are delivered to Codex as context—not automatic authorization

## Try it on a Galaxy S26 Ultra

### 1. Start the local bridge on the Mac

Clone this repo, then run:

```bash
export CODEX_SCRATCHPAD_ROOT="$PWD"
python3 bridge/mcp_server.py --host 0.0.0.0 --port 8787
```

Find the Mac's current Wi-Fi IP:

```bash
ipconfig getifaddr en0
```

Keep this terminal open. The bridge saves images under `~/.codex-scratchpad/inbox` by default.

### 2. Install the Android app

Open the project in Android Studio and run the `app` configuration on the phone, or build it:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On the phone, enter the bridge URL using the Mac IP, for example:

```text
http://192.168.1.42:8787
```

Draw, add an optional instruction, and tap **Push to Codex**.

### 3. Install the Codex plugin

Set the repo root before starting Codex:

```bash
export CODEX_SCRATCHPAD_ROOT="/absolute/path/to/codex-scratchpad"
```

Install the local plugin from `plugins/codex-scratchpad`. It exposes three MCP tools:

| Tool | Purpose |
| --- | --- |
| `scratchpad_latest` | Get the newest pending image and its caption |
| `scratchpad_list` | View the local inbox |
| `scratchpad_acknowledge` | Mark an image processed |

Then ask Codex: **“Read my newest scratchpad image.”**

## Security model

This MVP binds an HTTP receiver to your local Wi-Fi. Use it only on a trusted private network. It deliberately keeps data on your devices. Before using it beyond a trusted LAN, add TLS plus a pairing token; do not expose port `8787` to the public internet.

## Roadmap

- [ ] QR pairing + short-lived device token
- [ ] iOS / iPad client
- [ ] Screenshot and camera annotation
- [ ] Real-time device status in Codex
- [ ] Optional self-hosted remote relay

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=dreamhunter02/codex-scratchpad&type=Date)](https://www.star-history.com/#dreamhunter02/codex-scratchpad&Date)

## License

[MIT](LICENSE)
