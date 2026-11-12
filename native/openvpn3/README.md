# OpenVPN 3 Core Android native target

This directory contains the Mas Kiplay JNI boundary for a source-built OpenVPN
3 Core client. The CI workflow pins and checks out
`networks-rs/openvpn-connect-rs` at commit
`98fcf3ba0abfddb9f2458daace5ab2182052054b`, initializes its upstream
OpenVPN 3 and Asio submodules, and cross-compiles the native library for
`aarch64-linux-android` using the Android NDK.

The binding is an intentional first integration stage: it exposes only a
native-core capability probe. It does not yet create a VPN session, establish
Android's TUN interface, protect transport sockets, or route device traffic.
Do not connect the UI to this library or claim full VPN functionality until
the Kotlin `VpnService` adapter, JNI session lifecycle, profile validation,
and IPv4/IPv6/DNS leak tests are implemented and pass.

## Build

The workflow uses Rust 1.88+, Android NDK, and:

```sh
git clone https://github.com/networks-rs/openvpn-connect-rs.git native/openvpn-connect-rs
git -C native/openvpn-connect-rs checkout 98fcf3ba0abfddb9f2458daace5ab2182052054b
git -C native/openvpn-connect-rs submodule update --init --recursive
cargo build --manifest-path native/openvpn3/Cargo.toml --release --target aarch64-linux-android
```

OpenVPN 3 Core is licensed under MPL-2.0 OR AGPL-3.0-only WITH the OpenVPN 3
OpenSSL exception; the Rust bindings are MPL-2.0. Preserve all upstream
license and notice files when packaging the native library.
