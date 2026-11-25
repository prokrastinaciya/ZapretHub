#include <jni.h>

/*
 * Local JNI adapter (kept outside the pinned upstream snapshot) that exposes a
 * native-owned listener signal for the SOCKS5 engine session.
 *
 * The pinned engine calls listen() exactly once, for its SOCKS5 listener, in
 * listen_socket() immediately after a successful bind() (byedpi/proxy.c). By
 * intercepting that call we obtain positive proof that OUR engine owns the
 * bound listener: a foreign SOCKS5 responder occupying the port makes bind()
 * fail first, so listen() is never reached and the flag stays 0. Inferring
 * readiness from thread liveness or a handshake alone cannot give that proof.
 */
int __real_listen(int sockfd, int backlog);

static int g_listener_ready = 0;

int __wrap_listen(int sockfd, int backlog) {
    int result = __real_listen(sockfd, backlog);
    if (result == 0) {
        __atomic_store_n(&g_listener_ready, 1, __ATOMIC_SEQ_CST);
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_com_amurcanov_tgwsproxy_dpi_DpiNative_isListenerReady(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    return __atomic_load_n(&g_listener_ready, __ATOMIC_SEQ_CST);
}
