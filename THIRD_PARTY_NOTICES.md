# Third-party software and notices

## OpenVPN 3 Core (planned embedded client engine)

- Upstream: https://github.com/OpenVPN/openvpn3
- License: GNU Affero General Public License v3.0 **or** Mozilla Public License 2.0 (dual-licensed upstream).
- Intended selection for the embedded engine: AGPL-3.0, subject to a complete source and notice audit before distribution.
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
