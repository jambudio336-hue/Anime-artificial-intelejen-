# Mas Kiplay implementation status and QA plan

## Implemented in this branch

- Native speech permission results are returned to the JavaScript bridge after Android's runtime permission dialog.
- Jarvis and the chat microphone share one native speech recognizer but retain separate session contexts, so chat dictation does not overwrite Jarvis routing.
- The chat microphone checks the current Android permission state rather than trusting a stale cached capability value.
- The chat prompt instructs the model to proofread Indonesian spelling, punctuation, terminology, and factual consistency.
- Chat answers receive a second proofreading pass before being saved and rendered. Fenced code/action blocks are masked and restored so code, URLs, commands, and machine-readable blocks are not intentionally rewritten.
- Chat composer enables the platform spellchecker for user input.

## Not implemented yet — do not advertise as available

- OpenVPN 3 Core is not yet embedded or built for Android ABIs.
- The app does not yet fetch or parse VPN Gate OpenVPN profiles.
- Smart Connect, relay latency ranking, automatic reconnect, and server favorites are not yet implemented.
- The current VPN service remains a DNS-only IPv4 tunnel. It does not carry general app/device traffic.
- Full IPv4/IPv6 routing, kill-switch behavior, DNS/IP leak tests, and physical-device VPN acceptance tests have not passed.
- The app build pipeline still packages the existing Capacitor/WebView application; this branch does not convert the app to a fully native UI.
- No new signed APK has been built or published from this branch.

## Manual Jarvis and chat QA

1. Install a debug build on Android 13+ with microphone permission initially denied.
2. Open Jarvis, tap the microphone, grant permission, and verify that Indonesian speech is transcribed and submitted once.
3. Deny permission, retry, then grant it from Android App Info and retry without restarting the app.
4. Use chat dictation, then Jarvis dictation; verify each result reaches only its intended screen.
5. Revoke microphone permission in Settings and repeat the flow.
6. Send Indonesian prose, Markdown, fenced Kotlin/JavaScript, URLs, shell commands, and tool/action blocks.
7. Verify the proofreading pass does not alter fenced code, URLs, commands, or action blocks. Confirm the original answer is retained if the proofreading request fails.

## VPN acceptance gate

Do not mark VPN as connected until OpenVPN 3 reports a connected session, Android's TUN interface is established, the expected IPv4/IPv6 routes and DNS are installed, and external exit-IP/DNS tests pass. Test consent denial, server failure, network handoff, process death, revoke, disconnect, and kill-switch behavior on physical devices.

VPN Gate relays are volunteer operated. Display each relay's reported logging policy and do not promise anonymity or zero logging.

## Suggested product additions

- VPN kill switch and leak-protection status panel.
- Per-app split tunneling with an explicit allowlist.
- Relay favorites, country filters, live RTT, throughput estimate, and last-checked time.
- Session traffic meter and connection history stored locally.
- Wi-Fi auto-connect rules and a trusted-network bypass list.
- In-app privacy report showing actual exit IP, DNS resolver, IPv6 status, and test timestamp.
- Authorized local-network inventory with explicit target confirmation and rate limits.
- Chat export/search, response regeneration, and per-chat model settings.
- Offline speech-to-text fallback when the Android recognition service is unavailable.
