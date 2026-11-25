package com.amurcanov.tgwsproxy.ui

import com.amurcanov.tgwsproxy.ServiceSelection

internal data class HomePowerPlan(
    val startTelegram: Boolean = false,
    val startDpi: Boolean = false,
    val stopTelegram: Boolean = false,
    val stopDpi: Boolean = false,
)

internal fun homePowerPlan(selection: ServiceSelection, telegramActive: Boolean, dpiActive: Boolean, dpiPending: Boolean): HomePowerPlan =
    if (telegramActive || dpiActive || dpiPending) HomePowerPlan(stopTelegram = telegramActive, stopDpi = dpiActive || dpiPending)
    else HomePowerPlan(startTelegram = selection.telegram, startDpi = selection.needsDpi)
