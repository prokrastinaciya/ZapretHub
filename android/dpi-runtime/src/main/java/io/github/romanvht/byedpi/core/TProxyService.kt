package io.github.romanvht.byedpi.core

/**
 * JNI entry points for the HEV SOCKS5 tunnel, kept for the follow-up VPN mode
 * (task 3). The SOCKS5 local-proxy mode does not use it; `startTunnel` lazily
 * loads `libhev-socks5-tunnel.so`, which is only packaged in the VPN work.
 * Class name is fixed by the unchanged upstream `hev-jni.c`; do not rename.
 */
object TProxyService {
    init {
        System.loadLibrary("byedpi")
    }

    external fun startTunnel(config: String, fd: Int): Int

    external fun stopTunnel()
}
