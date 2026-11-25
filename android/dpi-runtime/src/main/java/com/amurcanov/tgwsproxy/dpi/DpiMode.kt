package com.amurcanov.tgwsproxy.dpi

/**
 * The two ByeByeDPI run modes. [SOCKS5] serves a local loopback proxy only;
 * [VPN] captures the system TUN slot and is implemented in a follow-up task.
 * Keeping both here preserves the public interface the UI and VPN layers build on.
 */
enum class DpiMode { VPN, SOCKS5 }
