package com.amurcanov.tgwsproxy.dpi

/**
 * Local JNI adapter (outside the pinned snapshot) exposing the native-owned
 * listener signal. [isListenerReady] becomes non-zero only after the pinned
 * engine has successfully called `listen()` on a socket it already bound — a
 * foreign SOCKS5 responder can never satisfy it.
 */
object DpiNative {
    init {
        System.loadLibrary("byedpi")
    }

    external fun isListenerReady(): Int
}
