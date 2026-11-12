# Watch, WireGuard, and Nmap integration

## Architecture and source layout

The CI workflow generates the Capacitor Android project from `www/` and copies
the Kotlin source from `pkg/kotlin/` into the generated app source tree. Watch
classes use the required Android package namespace `id.maskiplay.*`.

- `id.maskiplay.watch.WatchProvider`: common provider contract and models.
- `MeloloProvider`: keyless Melolo search, directory, and on-demand stream resolver.
- `AnimeProvider`: keyless Hianime-compatible mirror fallback and custom mirror setting.
- `ManualProvider`: session-local HTTP(S) MP4/HLS URL input.
- `WatchHttp`: OkHttp client, 15-second timeouts, retry, and request throttling.
- `WatchPlugin`: Capacitor bridge. Network calls run on a native worker executor.
- `PlayerActivity`: Media3 ExoPlayer, HLS/progressive sources, quality selection,
  WebVTT subtitles, and playback position persistence.
- `WatchDb`: Room watchlist, history, and progress tables.
- `SyncWorker`: periodic six-hour local history maintenance.
- `VpnPlugin`: WireGuard GoBackend control and Android VPN permission flow.
- `VpnStore`: AES-GCM encrypted configuration at rest using Android Keystore.

The web screen is `watch.html`, `watch.js`, and `watch.css`. It is copied into
the existing Capacitor `www/` web root by both Android workflows. The main menu
links to this screen.

## Keyless endpoints

### Dracin — Melolo

Base URL: `https://api.tmtreader.com`

Common query parameters:

- `app_id=645713`
- `version_name=5.1.8`
- `device_id=7268586056781432322`

Search:

```http
GET /i18n_novel/search/page/v1/?query={q}&offset={n}&count=20&{common}
```

The parser reads `data.search_data[].books[0]` and maps book ID, title, cover,
synopsis, episode count, status, and language.

Episode directory:

```http
GET /i18n_novel/book/directory/info/v1/?book_id={id}&{common}
```

The parser reads `data.item_list[]`; paid IDs are mapped to locked episodes.
Video resolution is performed only when the user chooses an episode. The JSON
payload uses integer values for `video_platform=1024` and `video_id_type=0`.
The current resolver posts the supplied payload to `/video_model`; this exact
path and response envelope still require a live-device/API verification before
release. The parser decodes Base64 `main_url` values and considers backup URLs.
Only HTTPS Melolo static stream hosts and up to 720p variants are accepted.

The bookmall endpoint is intentionally not used. Popular home discovery should
use the query set `love, ceo, revenge, marriage, heir, rebirth` with de-duplication;
the initial screen currently exposes search rather than a preloaded home catalogue.

### Anime

The provider tries these bases in order:

1. `https://aniwatch-api-tau.vercel.app/api/v2/hianime`
2. `https://aniwatch-api-rouge.vercel.app/api/v2/hianime`
3. `https://api-anime-rouge.vercel.app/aniwatch`

Endpoints:

- `GET /search?q={query}`
- `GET /anime/{id}/episodes`
- `GET /episode/sources?animeEpisodeId={episodeId}&server=hd-1&category=sub`

A successful mirror is cached for 24 hours; failed mirrors are skipped for one
hour. Settings exposes `Watch.setAnimeBase({url})` and requires HTTPS. The
resolver accepts HTTPS stream URLs and WebVTT subtitle tracks returned by the
mirror. Availability and response shape depend on third-party mirror uptime.

### Manual source

The Manual tab accepts HTTP(S) URLs ending in `.mp4` or `.m3u8`. This path does
not depend on either catalogue API and is session-local.

## Playback behavior and limitations

- Media3 ExoPlayer is used in a native Android Activity; WebView video is not used
  as the primary player.
- HLS uses `HlsMediaSource`; MP4 and other progressive URLs use
  `ProgressiveMediaSource`.
- Provider Referer headers are passed through the native HTTP data source.
- Quality variants are sorted by their quality label; the selected stream is
  passed to the player. The Melolo parser is limited to 720p.
- WebVTT tracks are attached when the anime mirror returns valid HTTPS subtitle
  URLs.
- Playback progress is persisted to Room and local preferences at five-second
  intervals. History and watchlist are stored in Room.
- Previous/next episode controls stay in the same PlayerActivity. Vertical
  swipes on the video surface adjust brightness (left half) and volume (right half).
- Some streams may use HEVC/bytevc1; support depends on the Android device's
  hardware decoder. External-player fallback is shown when the player reports
  a decoder/codec error.
- The actual video/audio playback and subtitle rendering must still be checked
  on physical Android hardware; CI cannot establish successful playback.

## WireGuard

The WireGuard Android tunnel library `1.0.20230706` is Apache-2.0 and uses
`GoBackend`, not a hand-written raw `VpnService` tunnel. The app requests VPN
consent before bringing the tunnel up, validates and parses the user-provided
WireGuard config, stores the config encrypted with Android Keystore AES-GCM,
and exposes up/down/status/keypair methods through the Capacitor `Vpn` plugin.

The existing DNS-only tunnel remains a separate legacy feature; it is not
represented as the WireGuard full tunnel. A successful WireGuard connection
still needs device-level verification (permission prompt, handshake, routes,
DNS, IPv4/IPv6 leak behavior, disconnect, revoke, and network handoff).

## Nmap

The native Capacitor `Nmap` plugin and Cyber Lab UI are present. The plugin
extracts `assets/nmap/nmap` into app-private storage, executes a fixed
unprivileged TCP connect scan (`-sT -Pn -T4 -F --host-timeout 30s -oX -`),
and returns raw XML. The host field accepts only an IP address or hostname;
scan only systems you own or are authorized to test.

**Release blocker:** no arm64 static `assets/nmap/nmap` binary is committed or
built by CI yet. The UI will report that the binary is missing until that asset
is supplied. The Nmap binary license and its source/notice obligations must be
audited before bundling it.

## Acceptance test report

Status reflects only evidence available from repository CI and code inspection.
A green web validation is not evidence of network-provider correctness or
physical-device media/VPN behavior.

| # | Acceptance test | Status |
|---|---|---|
| 1 | `./gradlew :app:assembleDebug` succeeds | Pending latest CI; previous CI attempts exposed and fixed workflow injection errors |
| 2 | Dracin search for `love` returns at least five cards with covers | Not run against live API |
| 3 | Open a Dracin title and load episodes | Not run against live API |
| 4 | Play episode 1 with moving video, audio, duration, and quality switching | Not run on physical device |
| 5 | Search anime, play an episode, and render returned WebVTT subtitle | Not run against live mirrors/device |
| 6 | Paste a manual MP4 URL and play it | Not run on physical device |
| 7 | Disable network and verify a recoverable error state without crash | Not run on physical device |
| 8 | WireGuard prepare → consent → up → AKTIF → down → MATI | Not run on physical device |
| 9 | Nmap scan of 127.0.0.1 returns XML with ports | Blocked: arm64 Nmap binary and plugin not integrated |
| 10 | Jarvis, chat, STT, TTS, and accessibility remain functional | Regression test not run on physical device |
| 11 | No API key or Bearer token is required by the new integrations | Static source inspection pending final audit |

Do not publish a release APK or merge this branch as complete until all eleven
acceptance tests pass and the Nmap binary blocker is resolved.
