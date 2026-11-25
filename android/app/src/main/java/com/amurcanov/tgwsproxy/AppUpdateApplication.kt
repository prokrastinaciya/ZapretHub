package com.amurcanov.tgwsproxy

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File

class AppUpdateApplication : Application() {
    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // AndroidX Startup initializes WorkManager in the main process only.
        if (currentProcessName() != applicationInfo.processName) return
        // 1.1.0 downloaded update APKs into cache/updates before the in-app
        // installer was removed. Drop leftovers from an older installation so
        // no APK stays behind after the upgrade.
        updateScope.launch { File(cacheDir, "updates").deleteRecursively() }
        if (!BuildConfig.APP_UPDATES_ENABLED) {
            // Also removes work retained from a previously installed public build.
            AppUpdateScheduler.schedule(this, UPDATE_CHECK_NEVER)
            return
        }
        updateScope.launch {
            SettingsStore(this@AppUpdateApplication).updateCheckIntervalHours.distinctUntilChanged().collect { hours ->
                AppUpdateScheduler.schedule(this@AppUpdateApplication, hours)
            }
        }
    }

    private fun currentProcessName(): String? =
        if (Build.VERSION.SDK_INT >= 28) getProcessName()
        else getSystemService(ActivityManager::class.java)?.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }?.processName
}
