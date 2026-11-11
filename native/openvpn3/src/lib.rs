//! JNI boundary for the OpenVPN 3 Core Android runtime.
//!
//! This initial native target proves that the pinned OpenVPN 3 Core and its
//! Rust bindings can be cross-compiled for Android. It intentionally exposes
//! only a capability probe; VPN connection remains disabled until the Android
//! TunBuilder and VpnService socket-protection callbacks are implemented.

use jni::objects::JClass;
use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_maskiplay_app_OpenVpn3Native_nativeCoreReady(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let caps = openvpn_connect::capabilities();
    if caps.tun_builder { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_maskiplay_app_OpenVpn3Native_nativeCoreInfo(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let text = format!(
        "OpenVPN 3 Core native library loaded; platform={}; tun_builder={}; external_tun={}",
        openvpn_connect::platform(),
        openvpn_connect::capabilities().tun_builder,
        openvpn_connect::capabilities().external_tun
    );
    env.new_string(text)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
