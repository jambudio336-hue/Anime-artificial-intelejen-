# Third-party software and notices

## OpenVPN 3 Core (native Android build stage)

- Upstream: https://github.com/OpenVPN/openvpn3
- The build uses `networks-rs/openvpn-connect-rs` revision `98fcf3ba0abfddb9f2458daace5ab2182052054b` (MPL-2.0), which source-builds OpenVPN 3 Core and exposes safe client/TUN callbacks.
- Android native build revision: `0b27d9df493db8bc9dc459a2b1cb6b67037742b2`.
- License options: MPL-2.0 OR AGPL-3.0-only WITH OpenVPN 3 OpenSSL exception. The project must select and document the applicable distribution path before publishing binaries.
- OpenVPN 3 is a C++ client library with an API suitable for a platform VPN/TUN adapter. It is not the same codebase/license as OpenVPN 2.
- Upstream license and Android integration documentation must be included with any binary distribution that contains the engine.

## VPN Gate public relay network

- Official service: https://www.vpngate.net/
- VPN Gate is a volunteer-operated public relay network maintained as an academic project by the University of Tsukuba.
- Server availability, performance, operator, and logging practices vary. The official listing reports a two-week logging policy for many relays; do not market the service as a zero-log or privacy-guaranteed VPN.
- Mas Kiplay is an independent client and is not endorsed by the University of Tsukuba or individual relay operators.
- Users must review the current VPN Gate terms and anti-abuse policy before connecting.

## Distribution compliance checklist

Before publishing an APK containing OpenVPN 3:
1. Pin the exact upstream commit and record its license files.
2. Include the full applicable license texts and copyright notices in the APK and source repository.
3. Publish the complete corresponding source, including native build scripts and modifications, in accordance with the selected license.
4. Verify every transitive dependency and bundled binary license.
5. Provide an in-app About / Open Source Licenses screen.
6. Do not claim the VPN is connected until the native engine reports a successful tunnel and route installation.
7. The current JNI native target exposes only a core capability probe. The Android `TunBuilder`, `VpnService.protect()` socket callback, and live VPN session lifecycle are not yet integrated; the active service remains DNS-only IPv4.
