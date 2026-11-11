# OpenVPN 3 Core Android integration

## Decision

Mas Kiplay will use the upstream OpenVPN 3 Core client API from
[OpenVPN/openvpn3](https://github.com/OpenVPN/openvpn3), pinned to a reviewed
commit. The core is a C++ client library; it is not an Android VPN app and it
does not create the Android TUN interface by itself. The Android application
must provide the platform callbacks and packet I/O.

Upstream references:

- Client API: `client/ovpncli.hpp`
- Android TUN abstraction: `openvpn/tun/builder/base.hpp`
- Generic TUN client: `openvpn/tun/builder/client.hpp`
- Core developer guide: https://openvpn.github.io/openvpn3/
- Android VPN lifecycle: https://developer.android.com/develop/connectivity/vpn

## Current repository status

Implementation stage (current branch):

- A source-built Android arm64 native target now uses the pinned
  `networks-rs/openvpn-connect-rs` binding and its pinned OpenVPN 3 Core /
  Asio submodules.
- A JNI capability probe and guarded Kotlin loader are present. The APK build
  workflow is being updated to package the native library.
- This is **not yet a working OpenVPN session**: no Android `TunBuilder`
  adapter, `VpnService.protect(fd)` callback, session lifecycle, or live
  profile-to-Core connection is wired.

The currently usable `MasKiplayVpnService` remains a DNS-only IPv4 tunnel.
VPN Gate server discovery remains a catalogue operation. Do not label the
native library probe or DNS Shield as a full VPN. Keep the UI honest until the
runtime acceptance gate below passes.

## Target architecture

```text
Web UI / VPN panel
      |
      v
NativeBridge (typed commands + state events)
      |
      v
MasKiplayVpnService : Android VpnService
      |                         |
      | Android TUN fd           | protect(socket fd)
      v                         v
OpenVPN 3 TunBuilderBase callbacks
      |
      v
OpenVPN 3 ClientAPI::OpenVPNClient
      |
      v
TLS / control channel / encrypted data channel
      |
      v
VPN Gate relay (or user-supplied compatible .ovpn endpoint)
```

### Native engine boundary

- Build the upstream Core for each shipped Android ABI with the Android NDK.
  Initial release ABI: `arm64-v8a`; add `armeabi-v7a`, `x86_64`, and
  `x86` only after CI and device coverage.
- Use C++20 as required by the pinned upstream Core revision.
- Expose a narrow JNI/C ABI; do not expose C++ objects directly to Kotlin.
- Own one engine/session instance per VPN service process.
- Run the blocking `OpenVPNClient::connect()` lifecycle on a dedicated native
  worker thread. Marshal events/logs to Kotlin without blocking the UI thread.
- Validate and evaluate profile configuration before connecting. Do not run
  profile scripts, shell commands, or arbitrary external executables.
- Keep credentials and private keys out of logs, crash reports, and analytics.

### Android TUN and socket protection

Implement the OpenVPN 3 `TunBuilderBase` callbacks in the Android adapter:

- `tun_builder_new`
- `tun_builder_set_layer` (TUN / layer 3 only)
- `tun_builder_set_remote_address`
- `tun_builder_add_address`
- `tun_builder_reroute_gw`
- `tun_builder_add_route` and `tun_builder_exclude_route`
- `tun_builder_set_dns_options`
- `tun_builder_set_mtu`
- `tun_builder_establish`
- `tun_builder_teardown`

The adapter must translate the negotiated addresses, routes, DNS servers,
search domains, and MTU into Android's `VpnService.Builder`, then return the
established TUN file descriptor to Core. Core's transport socket protection
callback must call `VpnService.protect(fd)` before the socket is used, avoiding
recursive routing into the VPN itself.

Never report CONNECTED merely because the Android TUN descriptor exists.
CONNECTED requires successful OpenVPN negotiation, a usable TUN descriptor,
installed routes/DNS, and an active session.

### Full-tunnel and leak protection

- Install the server-negotiated IPv4 and IPv6 addresses and routes.
- Honor the server's default-route intent; do not silently replace it with a
  DNS-only route.
- If IPv6 is not supported by the selected profile/adapter, explicitly block
  IPv6 while connected instead of allowing it to bypass the tunnel.
- Apply DNS settings from the negotiated profile and verify the resolver path.
- Provide an optional Android always-on / lockdown setup guide; do not enable
  device-wide lockdown without explicit user action.
- On disconnect, revoke, process death, or unrecoverable tunnel error, close
  the TUN descriptor and native session cleanly. Report DISCONNECTED/FAILED.
- Do not promise a kill switch unless Android lockdown or equivalent packet
  blocking has actually been configured and tested.

## VPN Gate profile handling

- Fetch the public VPN Gate catalogue over HTTPS and parse defensively.
- Treat catalogue ping/speed/session values as untrusted, time-sensitive
  metadata; recheck reachability before selecting a relay.
- Import only OpenVPN profiles. Validate endpoint host/port and supported
  directives before passing the merged inline profile to Core.
- Reject profiles containing unsupported or unsafe directives; never execute
  `up`, `down`, `route-up`, `ipchange`, plugin, or script commands.
- Display that VPN Gate relays are volunteer-operated. Do not claim they are
  audited, private, anonymous, or zero-log services.
- Support user-supplied compatible `.ovpn` profiles as a separate source.

## UI state contract

The bridge should expose explicit states:

`IDLE -> PERMISSION_REQUIRED -> CONNECTING -> CONNECTED`

Failure paths:

`CONNECTING -> AUTH_REQUIRED | FAILED | DISCONNECTED`

Disconnect path:

`CONNECTED -> DISCONNECTING -> DISCONNECTED`

Every state event includes a timestamp and a safe, user-readable reason.
Expose selected relay, negotiated protocol/transport, duration, RX/TX counters,
and last failure reason only when those values are available from Core. Do not
derive "connected" from a switch position or a static boolean.

## CI and release requirements

- Pin the exact upstream OpenVPN 3 Core commit; never build an unpinned
  `master` checkout for a release.
- Build native libraries for every declared ABI using NDK CMake toolchains.
- Run Core unit tests on a supported host build where feasible.
- Verify native library ABI packaging, symbols, license notices, and APK
  signature.
- Retain third-party notices for OpenVPN 3 and every bundled dependency.
- Do not publish a release APK until native build and app build both pass.

## Acceptance gate (all required)

1. First-use VPN permission is requested. Denial leaves normal networking
   untouched and the app reports permission denied.
2. A valid profile negotiates a real OpenVPN session and the system VPN/key
   indicator appears.
3. IPv4 traffic from more than one app exits through the selected relay.
4. IPv6 traffic is tunneled or demonstrably blocked; no IPv6 bypass.
5. DNS leak test confirms queries use the configured tunnel resolver.
6. Transport sockets are protected and do not recurse through the TUN.
7. Invalid profile, bad credentials, unreachable relay, timeout, and revoked
   permission all produce truthful failure states and cleanup.
8. Wi-Fi/mobile handoff, airplane mode, app backgrounding, process recreation,
   and explicit disconnect are tested on physical Android devices.
9. Exit IP and DNS checks are captured before/during/after the session.
10. A successful release build is installed and smoke-tested on the supported
    Android API range and ABI.

Until all ten checks pass, keep the existing DNS-only implementation labelled
as DNS Shield and do not advertise it as a full VPN.
