package com.amurcanov.tgwsproxy

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat

/** What the system has to allow for the proxy to keep working with the screen off. */
object SystemAccess {
    enum class Item { Notifications, Battery, Background, OemAutostart }

    /** Items without which Android may stop the proxy in the background or hide its controls. */
    val critical = setOf(Item.Notifications, Item.Background)

    // Firmware with its own autostart switch. Android exposes no status for it, so it can only be opened.
    private val autostartScreens: Map<String, List<Pair<String, String>>> = mapOf(
        "xiaomi" to listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        "redmi" to listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        "poco" to listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        "huawei" to listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        ),
        "honor" to listOf(
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
        "oppo" to listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        ),
        "realme" to listOf("com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        "oneplus" to listOf("com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        "vivo" to listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        ),
        "asus" to listOf("com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity"),
    )

    private val manufacturer: String get() = Build.MANUFACTURER.orEmpty().lowercase()

    private fun isBackgroundRestricted(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true

    /** Rows that apply to this device right now. */
    fun items(context: Context): List<Item> = buildList {
        add(Item.Notifications)
        add(Item.Battery)
        if (isBackgroundRestricted(context)) add(Item.Background)
        if (manufacturer in autostartScreens) add(Item.OemAutostart)
    }

    /** True or false when the platform can tell; null when the user has to check it by eye. */
    fun isGranted(context: Context, item: Item): Boolean? = when (item) {
        Item.Notifications -> NotificationManagerCompat.from(context).areNotificationsEnabled()
        Item.Battery -> context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
        Item.Background -> !isBackgroundRestricted(context)
        Item.OemAutostart -> null
    }

    /** Opens the screen that changes this item. Notifications on Android 13+ go through the runtime prompt first. */
    fun open(context: Context, item: Item) {
        val opened = when (item) {
            Item.Notifications -> tryStart(context, notificationSettings(context)) || tryStart(context, appDetails(context))
            Item.Battery -> tryStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) ||
                tryStart(context, appDetails(context))
            Item.Background -> tryStart(context, appDetails(context))
            Item.OemAutostart -> autostartScreens[manufacturer].orEmpty().any { (pkg, cls) ->
                tryStart(context, Intent().setComponent(ComponentName(pkg, cls)))
            } || tryStart(context, appDetails(context))
        }
        if (!opened) {
            Toast.makeText(context, R.string.background_request_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun packageUri(context: Context) = Uri.parse("package:${context.packageName}")

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    private fun notificationSettings(context: Context) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            appDetails(context)
        }

    // Vendor screens may be missing, renamed or closed to other apps on any given firmware version.
    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}
