package com.amurcanov.tgwsproxy.ui.dpi

import com.amurcanov.tgwsproxy.ServiceSelection
import com.amurcanov.tgwsproxy.dpi.DpiStrategyResult
import com.amurcanov.tgwsproxy.dpi.dpiTargetsFor
import com.amurcanov.tgwsproxy.dpi.rankDpiStrategies

internal fun verifiedDpiResult(selection: ServiceSelection, results: List<DpiStrategyResult>): DpiStrategyResult? {
    val targets = dpiTargetsFor(selection)
    if (targets.isEmpty()) return null
    return rankDpiStrategies(results).firstOrNull { it.targets == targets && it.reached.containsAll(targets) && !it.startFailed }
}

internal fun showTelegramFallback(selection: ServiceSelection, configured: Boolean, port80: Boolean, running: Boolean): Boolean =
    selection.telegram && configured && !port80 && !running
