# Mas Kiplay implementation status and QA plan

## Implemented in this branch

- Native speech permission results are returned to the JavaScript bridge after Android's runtime permission dialog.
- Jarvis and the chat microphone share one native speech recognizer but retain separate session contexts, so chat dictation does not overwrite Jarvis routing.
- The chat microphone checks the current Android permission state rather than trusting a stale cached capability value.
- The chat prompt instructs the model to proofread Indonesian spelling, punctuation, terminology, and factual consistency.
- Chat answers receive a second proofreading pass before being saved and rendered. Fenced code/action blocks are masked and restored so code, URLs, commands, and machine-readable blocks are not intentionally rewritten.
- Chat composer enables the platform spellchecker for user input.


## Additional changes in the OpenVPN 3 preparation branch

- WebCheck and OSINT now open in a native in-app dialog WebView instead of launching the external browser.
- Google Dork result actions open Google results in the same isolated in-app browser.
- The in-app browser accepts HTTPS URLs only, blocks mixed content and invalid TLS certificates, disables file/content access and pop-up windows, and does not expose the app's JavaScript/native bridge to remote pages.
- Jarvis speech recognition now creates a fresh Android `SpeechRecognizer` per listening session, tags callbacks with a generation counter, ignores stale callbacks, and cancels/destroys the recognizer when stopped or when the bridge shuts down.
- CI now checks that WebCheck, OSINT, and dork actions are wired to the isolated in-app browser.

These changes have been committed to `feat/openvpn3-core-android`. They still require the GitHub Actions checks and physical-device verification before a public release.

## Not implemented yet — do not advertise as available

- OpenVPN 3 Core is not yet embedded or built for Android ABIs.
- The app now fetches the VPN Gate public CSV feed and extracts metadata from OpenVPN-capable entries, but does not yet pass the profile to an OpenVPN 3 engine.
- Smart Server Finder ranks the top 24 OpenVPN-capable relays using VPN Gate's reported ping/speed/session data and bounded TCP reachability probes. This is directory ranking only, not a working VPN connection or full Smart Connect.
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
