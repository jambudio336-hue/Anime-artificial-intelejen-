# VPN Gate + OpenVPN 3 integration plan

## Current state

The existing `MasKiplayVpnService` is a DNS-only IPv4 tunnel. It intercepts UDP/53 and forwards DNS packets to a resolver. It is **not** a full-device VPN and must not be represented as one.

## Target architecture

- VPN Gate catalog client: fetch the official public relay CSV over HTTPS, parse records defensively, and retain only rows containing an OpenVPN profile.
- Profile handling: decode the server-provided OpenVPN profile, validate its remote endpoint and directives, and never execute profile-provided scripts or shell commands.
- Native engine: embed OpenVPN 3 Core through the Android NDK/JNI boundary and implement the OpenVPN TUN builder callbacks using Android `VpnService`.
- Tunnel lifecycle: request Android VPN consent, protect the transport socket, install IPv4 and IPv6 routes/DNS only after successful negotiation, expose state/events, and close the engine and TUN descriptor on disconnect/revoke.
- Smart Connect: probe a bounded shortlist, rank by measured RTT and successful TCP/UDP reachability, then attempt a small number of candidates with backoff. Never infer a VPN endpoint from device location.
- UI bridge: expose server list, connect, disconnect, status, selected country, exit IP, and failure reason to the existing in-app VPN panel.

## Required acceptance tests

- APK builds for arm64-v8a; native symbols and licenses are packaged.
- VPN consent denial leaves the network unchanged.
- Successful connection routes IPv4 and IPv6 (or explicitly blocks unsupported IPv6 to prevent leaks).
- DNS requests use the selected tunnel resolver; no DNS leak in test.
- Transport socket is protected from recursive routing.
- Revoke, process death, airplane mode, Wi-Fi/mobile handoff, and server timeout cleanly tear down or reconnect.
- Exit IP differs from the direct IP and matches the selected relay country.
- Disconnect restores normal routing.
- The app reports connected only after engine + TUN + routes are confirmed.

## Important service limitations

VPN Gate relays are volunteer-operated and can disappear, become overloaded, or have different logging behavior. A nearby relay is not guaranteed. Smart Connect must use live measurements and display that these are public relays, not trusted commercial infrastructure.

## Implementation status

This document is an engineering plan, not evidence that the native engine has been integrated or that a VPN connection works. The current DNS-only implementation remains until the replacement engine passes the acceptance tests.
