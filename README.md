# Codex Scratchpad

[![Android](https://img.shields.io/badge/Android-26%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Codex](https://img.shields.io/badge/Codex-MCP-FF8A1E?logo=openai&logoColor=white)](https://developers.openai.com/)
[![License](https://img.shields.io/badge/License-MIT-181717.svg)](LICENSE)
[![GitHub stars](https://img.shields.io/github/stars/dreamhunter02/codex-scratchpad?style=social)](https://github.com/dreamhunter02/codex-scratchpad/stargazers)

**Your phone is a visual input surface for Codex.** Draw with a finger, S Pen, Apple Pencil, or any stylus; tap **Push to Codex**; your local Codex agent receives the image.

![Codex Scratchpad mobile concept](assets/mobile-concept.png)

## What it does

```text
phone / tablet ── Bonjour discovery ──> Codex Scratchpad on Mac ──MCP──> Codex
     draw + caption                        private PNG inbox          agent context
```

- Native Android canvas with finger + stylus pressure support
- Mobile and tablet responsive layout
- Zero-config local discovery: no Mac IP address or port to type
- Dependency-free Python bridge + MCP server
- No Firebase, account, cloud sync, or external image upload
- Image and optional caption are delivered to Codex as context—not automatic authorization

## Try it on a Galaxy S26 Ultra

1. Install the Android app.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

2. Install the Codex plugin.
3. Keep phone + Mac on the same Wi-Fi.
4. Open the app. It finds the Codex Scratchpad service automatically, then pairs once.
5. Draw, optionally add an instruction, and tap **Push to Codex**.

There is no IP address, port, terminal command, Firebase project, or cloud account in the user flow.

Codex receives the sketch through these private local MCP tools:

| Tool | Purpose |
| --- | --- |
| `scratchpad_latest` | Get the newest pending image and its caption |
| `scratchpad_list` | View the local inbox |
| `scratchpad_acknowledge` | Mark an image processed |

Ask Codex: **“Read my newest scratchpad image.”**

> **Implementation note:** the current source includes the Android canvas and local MCP inbox. Bonjour discovery + automatic pairing is the next slice; the temporary bridge-URL field is not the intended product experience and will be removed with that change.

## Security model

Scratchpad traffic stays on the local network and is intended for a trusted private Wi-Fi. Bonjour advertises the local receiver; a short-lived pairing secret authorizes the phone. No image is sent to a cloud service. Do not expose the receiver to the public internet.

## Roadmap

- [ ] Bonjour/mDNS automatic Mac discovery
- [ ] QR fallback + short-lived device pairing
- [ ] iOS / iPad client
- [ ] Screenshot and camera annotation
- [ ] Real-time device status in Codex
- [ ] Optional self-hosted remote relay

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=dreamhunter02/codex-scratchpad&type=Date)](https://www.star-history.com/#dreamhunter02/codex-scratchpad&Date)

## License

[MIT](LICENSE)
