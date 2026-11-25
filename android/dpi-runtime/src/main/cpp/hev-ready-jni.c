#include <jni.h>

/*
 * Local JNI adapter (kept outside the pinned upstream snapshot) exposing a
 * genuine native-ready signal for the HEV tunnel.
 *
 * The pinned tunnel calls hev_socks5_tunnel_run() exactly once, from
 * hev-main.c, only after hev_socks5_tunnel_init() succeeded (hev-main.c:74-78).
 * Intercepting that call with --wrap yields a positive signal that the native
 * event loop is about to run — unlike thread liveness, which cannot distinguish
 * "still initializing" from "running". A failed init returns before the call,
 * so the flag stays 0.
 */
int __real_hev_socks5_tunnel_run(void);

static int g_tunnel_ready = 0;

int __wrap_hev_socks5_tunnel_run(void) {
    __atomic_store_n(&g_tunnel_ready, 1, __ATOMIC_SEQ_CST);
    return __real_hev_socks5_tunnel_run();
}

JNIEXPORT jint JNICALL
Java_com_amurcanov_tgwsproxy_dpi_HevNative_isReady(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    return __atomic_load_n(&g_tunnel_ready, __ATOMIC_SEQ_CST);
}
