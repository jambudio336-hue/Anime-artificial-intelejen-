# Mas Kiplay implementation status and QA plan

## Implemented in the current OpenVPN 3 preparation branch

- Native speech permission results are returned to the JavaScript bridge after the Android runtime permission dialog.
- Jarvis and chat microphone sessions retain separate routing contexts; stale speech callbacks are ignored and recognizers are cleaned up on stop/shutdown.
- Chat dictation checks the current Android microphone permission rather than trusting a stale cached capability.
- Chat answers receive a proofreading pass while fenced code/action blocks are masked and restored.
- The in-app browser for WebCheck, OSINT, and Google Dork accepts HTTPS only, blocks mixed content and invalid TLS certificates, disables file/content access and pop-up windows, and does not expose the app JavaScript bridge to remote pages.
- CI validates JavaScript syntax and browser isolation wiring.
- A pinned OpenVPN 3 Core binding/native library is cross-built for Android arm64 and packaged into the debug APK. The JNI surface is currently a capability/status probe only.

## Latest verified CI state (2026-11-12)

Commit: `167b6663b6bea2198dbf3c8a4dd1eee10ddd422c`

- Android Debug Build #83: passed; artifact `MasKiplay-debug-83` uploaded.
- Validate Web App #142: passed.
- OpenVPN 3 Core Android Native Build #52: passed; artifact `maskiplay-openvpn3-arm64` uploaded.
- These are CI artifacts. A signed public release has not been verified or published from this branch.
- PR #9 (`feat/openvpn3-core-android` -> `main`) remains open and draft.

## VPN functionality — important limitation

The OpenVPN 3 native library is built and packaged, but **the app does not yet establish a real OpenVPN session**. The JNI layer exposes only a capability/status probe. The Android `MasKiplayVpnService` remains a DNS-only IPv4 tunnel and does not carry general device/app traffic through an OpenVPN relay.

Not implemented / not acceptance-tested:

- Android `TunBuilder` adapter and TUN file-descriptor lifecycle.
- Native OpenVPN client session lifecycle and profile handoff.
- `VpnService.protect(fd)` transport socket callback.
- Negotiated IPv4/IPv6 address, route, MTU, and DNS installation.
- IPv6 leak blocking/tunneling, DNS/IP leak tests, and verified full-tunnel behavior.
- Reconnect, network handoff, process-death, permission-revocation, and failure cleanup tests on physical devices.

VPN Gate server discovery and ranking are catalogue operations only. VPN Gate relays are volunteer-operated; do not claim they are audited, anonymous, private, or zero-log.

## Manual Jarvis and chat QA

1. Install a debug build on supported Android devices with microphone permission initially denied.
2. Open Jarvis, tap the microphone, grant permission, and verify Indonesian speech is transcribed and submitted once.
3. Deny permission, retry, then grant it from Android App Info and retry without restarting the app.
4. Use chat dictation, then Jarvis dictation; verify each result reaches only its intended screen.
5. Revoke microphone permission in Settings and repeat the flow.
6. Send Indonesian prose, Markdown, fenced Kotlin/JavaScript, URLs, shell commands, and tool/action blocks.
7. Verify proofreading does not alter fenced code, URLs, commands, or action blocks. Confirm the original answer is retained if proofreading fails.

## VPN acceptance gate

Do not mark VPN as connected until OpenVPN 3 reports a connected session, Android TUN is established, negotiated routes/DNS are installed, and external exit-IP/DNS tests pass. Test permission denial, invalid profile, bad credentials, server failure, network handoff, process death, revoke, disconnect, and kill-switch behavior on physical devices.

## Remaining product/QA work

- Complete and review the Android TUN + OpenVPN 3 session adapter before enabling full VPN controls.
- Add IPv4/IPv6 route and DNS leak tests plus reconnect/revoke lifecycle tests.
- Verify release signing secrets and expected certificate fingerprint before any release dispatch.
- Run signed release build, inspect APK contents/signature, install on physical devices, and only then publish.
- Consider split tunneling, relay favorites, session traffic counters, and a privacy report after core VPN acceptance passes.
