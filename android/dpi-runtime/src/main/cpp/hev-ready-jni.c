#include <jni.h>
#include <lwip/icmp.h>
#include <lwip/icmp6.h>
#include <lwip/inet_chksum.h>
#include <lwip/ip.h>
#include <lwip/pbuf.h>
#include <lwip/prot/icmp.h>
#include <lwip/prot/icmp6.h>
#include <lwip/prot/udp.h>
#include <lwip/udp.h>

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

// Reject QUIC's usual port inside the VPN. Other UDP (including DNS and
// typical voice traffic) keeps using the ordinary HEV proxy path.
static int block_udp_443;

void __real_udp_input(struct pbuf *packet, struct netif *interface);

static void reject_udp(struct pbuf *packet, struct netif *interface) {
    const u16_t ip_header_length = ip_current_header_tot_len();
    if (ip_header_length <= 1200 && pbuf_add_header_force(packet, ip_header_length) == 0) {
        // Quote the original headers. Use the original destination as the
        // ICMP source: HEV's virtual gateway itself is configured as loopback.
        const int v6 = ip_current_is_v6();
        const u16_t quote = ip_header_length + UDP_HLEN;
        const u16_t icmp_header_length = sizeof(struct icmp_hdr);
        struct pbuf *reply = pbuf_alloc(PBUF_IP, icmp_header_length + quote, PBUF_RAM);
        if (reply == NULL) return;
        struct icmp_hdr *header = reply->payload;
        header->type = v6 ? ICMP6_TYPE_DUR : ICMP_DUR;
        header->code = v6 ? ICMP6_DUR_PORT : ICMP_DUR_PORT;
        header->chksum = 0;
        header->data = 0;
        if (pbuf_copy_partial_pbuf(reply, packet, quote, icmp_header_length) == ERR_OK) {
            if (v6) {
                const ip6_addr_t *source = ip6_current_dest_addr();
                const ip6_addr_t *destination = ip6_current_src_addr();
                header->chksum = ip6_chksum_pseudo(reply, IP6_NEXTH_ICMP6, reply->tot_len, source, destination);
                ip6_output_if(reply, source, destination, 64, 0, IP6_NEXTH_ICMP6, interface);
            } else {
                header->chksum = inet_chksum(reply->payload, reply->len);
                ip4_output_if(reply, ip4_current_dest_addr(), ip4_current_src_addr(), 64, 0, IP_PROTO_ICMP, interface);
            }
        }
        pbuf_free(reply);
    }
}

void __wrap_udp_input(struct pbuf *packet, struct netif *interface) {
    struct udp_hdr header;
    if (!block_udp_443 || ip_current_header_proto() != IP_PROTO_UDP ||
        pbuf_copy_partial(packet, &header, sizeof(header), 0) != sizeof(header) ||
        header.dest != lwip_htons(443) ||
        ip_addr_ismulticast(ip_current_dest_addr()) ||
        ip_addr_isbroadcast(ip_current_dest_addr(), interface)) {
        __real_udp_input(packet, interface);
        return;
    }

    reject_udp(packet, interface);
    pbuf_free(packet);
}

JNIEXPORT void JNICALL
Java_com_amurcanov_tgwsproxy_dpi_HevNative_setBlockUdp443(JNIEnv *env, jobject thiz, jboolean blocked) {
    (void) env;
    (void) thiz;
    block_udp_443 = blocked == JNI_TRUE;
}
