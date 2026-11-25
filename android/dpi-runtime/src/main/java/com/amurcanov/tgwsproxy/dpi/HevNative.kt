package com.amurcanov.tgwsproxy.dpi

/**
 * Local JNI adapter (outside the pinned snapshot) exposing the genuine
 * native-ready signal of the HEV tunnel. [isReady] becomes non-zero only after
 * the pinned tunnel has reached `hev_socks5_tunnel_run()` — i.e. its native
 * event loop is about to run — which happens exactly once, only after
 * `hev_socks5_tunnel_init()` succeeded. A foreign or failed tunnel can never
 * satisfy it, and thread liveness is not used as a ready signal.
 */
object HevNative {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    external fun isReady(): Int
}
