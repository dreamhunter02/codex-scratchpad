# Security policy

## Supported versions

dev.board is an early preview. Security fixes are applied to the latest GitHub release.

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting feature instead of opening a public issue. Include:

- affected version
- reproduction steps
- expected and observed behavior
- potential impact
- suggested mitigation, if known

Do not include pairing tokens, private images, network addresses, or other sensitive data in reports.

## Security model

The bridge is intended for a trusted local network. It is not hardened as a public internet service. Do not port-forward it, expose it through a public tunnel, or bind it on an untrusted network.

