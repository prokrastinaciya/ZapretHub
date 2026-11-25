package com.amurcanov.tgwsproxy.dpi

/** Controller lifecycle state. `Running` and `Failed` are terminal relative to a start command. */
sealed interface DpiState {
    data object Off : DpiState
    data object Starting : DpiState
    data class Running(val mode: DpiMode) : DpiState
    data object Stopping : DpiState
    data class Failed(val reason: String) : DpiState
}
