# Contributing to dev.board

Thanks for helping make visual input to coding agents simpler.

## Development setup

1. Install JDK 17 and Android SDK 35.
2. Clone the repository.
3. Build and lint:

```bash
./gradlew :app:assembleDebug :app:lintDebug
```

4. Install the debug APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The bridge and Codex plugin use only Python's standard library.

## Pull requests

- Keep each pull request focused.
- Explain the user-visible behavior and privacy impact.
- Include screenshots for UI changes.
- Run the build and lint commands above.
- Never commit API keys, pairing secrets, keystores, inbox images, or local configuration.

## Design principles

- Preserve the canvas-first interface.
- Keep the normal workflow zero-config.
- Prefer local-network and local-filesystem data paths.
- Make controls understandable with both icons and accessibility descriptions.
- Keep finger input, generic styluses, and S Pen behavior equally supported.

