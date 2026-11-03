package dev.shield.core.vpn

import dev.shield.data.TunnelMode

enum class TunnelPhase { STOPPED, STARTING, RUNNING, ERROR }

/** Live state of the tunnel, shown in dashboard, widget, tile and debug screen. */
data class TunnelState(
    val phase: TunnelPhase = TunnelPhase.STOPPED,
    val mode: TunnelMode = TunnelMode.DNS_ONLY,
    val startedAt: Long = 0,
    val error: String? = null,
    val wgProfileName: String? = null,
    val wgWanted: Boolean = false,
    val wgConnected: Boolean = false,
    val wgSince: Long = 0,
    val handshakeAgeSec: Long = -1,
    val panic: Boolean = false,
    val killSwitch: Boolean = false,
    val addresses: List<String> = emptyList(),
    val routes: List<String> = emptyList(),
    val dnsServers: List<String> = emptyList(),
    val mtu: Int = 0,
    val upstream: String = "",
    val alwaysOn: Boolean = false,
    val lockdown: Boolean = false,
    val coreStats: String = "",
) {
    val running: Boolean get() = phase == TunnelPhase.RUNNING
}
