# Mas Kiplay — AI Agent Android

Mas Kiplay v9.4.1 is an Android application and PWA that combines an AI chat/Jarvis interface, on-device tools, web research utilities, and an in-app video catalogue.

## Main capabilities

- **Chat and Jarvis:** AI chat, speech input/output, and native Android integration through explicit user permissions.
- **Device tools:** native bridge for supported device actions; sensitive actions should remain user-confirmed.
- **WebCheck and OSINT:** HTTPS-only in-app browser for supported web services. Some sites may restrict embedded browsers or require their official app/browser.
- **Watch:** Melolo drama catalogue, anime provider integration, episode directories, watch history, watchlist, and an in-app Media3 player.
- **Automatic catalogue refresh:** WorkManager refreshes the public Melolo catalogue in the background every six hours and keeps a local fallback. Episode directories and playback URLs are refreshed when the title/episode is opened.
- **Startup media:** `video.mp4` is used for the opening splash; `Dashboard.mp4` is the looping dashboard background.
- **VPN:** native WireGuard GoBackend integration accepts a valid user/server configuration. Configurations are parsed before being persisted and stored with Android Keystore AES-GCM. A generated keypair alone is not a VPN connection. Full-tunnel behavior depends on a correctly configured peer and routes.
- **Security utilities:** an unprivileged TCP connect scanner for 51 common ports on one authorized host, plus web/OSINT utilities. The scanner is not Nmap and does not perform UDP, SYN, OS fingerprinting, or subnet scans.

## Build and release

The public Android release is built by GitHub Actions. The workflow stages the web assets, copies native Kotlin sources, resolves Android dependencies, signs the release APK from repository secrets, and publishes the artifact when the workflow is configured for a release.

Do not treat a successful CI build as proof of real-device functionality. VPN routing, media playback, WebView compatibility, and permission-dependent device actions still require testing on supported Android devices.

## Media refresh details

See [docs/MEDIA_CATALOGUE_REFRESH.md](docs/MEDIA_CATALOGUE_REFRESH.md) for refresh cadence, offline fallback, media asset naming, and provider limitations.

## In-app website compatibility

The internal browser routes HTTPS pages and user-initiated popups into the same WebView, supports JavaScript, DOM storage, cookies, file selection, and media playback. HTTP navigation and invalid TLS certificates are blocked. Google OAuth is not supported inside embedded WebViews by Google's security policy; sign-in may require a trusted browser or Android Custom Tabs. Some third-party services may independently block WebView, DRM playback, CAPTCHA, or uploads.

## VPN status

WireGuard is the supported full-tunnel engine when a valid peer configuration is supplied. The legacy DNS-only tunnel is not a device-wide VPN. OpenVPN 3 remains under development in [PR #9](https://github.com/jambudio336-hue/Anime-artificial-intelejen-/pull/9) and must not be represented as ready until its TUN/session adapter and leak tests are complete.

## Licensing

See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for project and dependency licensing.
