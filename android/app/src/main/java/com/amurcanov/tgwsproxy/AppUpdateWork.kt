package com.amurcanov.tgwsproxy

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Android persists this schedule across Activity/process destruction and reboots. */
object AppUpdateScheduler {
    const val UNIQUE_WORK = "tgws-public-release-check"

    internal fun periodicRequest(enabled: Boolean, intervalHours: Int): PeriodicWorkRequest? {
        if (!enabled || intervalHours == UPDATE_CHECK_NEVER) return null
        val hours = intervalHours.takeIf { it > 0 } ?: DEFAULT_UPDATE_CHECK_INTERVAL_HOURS
        return PeriodicWorkRequestBuilder<AppUpdateWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            // The foreground check handles first launch. A periodic request's
            // default immediate first run would duplicate it.
            .setInitialDelay(hours.toLong(), TimeUnit.HOURS)
            .build()
    }

    fun schedule(context: Context, intervalHours: Int): Operation {
        val manager = WorkManager.getInstance(context.applicationContext)
        val request = periodicRequest(BuildConfig.APP_UPDATES_ENABLED, intervalHours)
        return if (request == null) manager.cancelUniqueWork(UNIQUE_WORK)
        else manager.enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

class AppUpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        // Check the installed build flag again: an update can change a public
        // installation into a private build while work is already enqueued.
        if (!BuildConfig.APP_UPDATES_ENABLED) return Result.success()
        return try {
            if (SettingsStore(applicationContext).updateCheckIntervalHours.first() == UPDATE_CHECK_NEVER) {
                Result.success()
            } else {
                // Metadata only. Never downloads an APK, opens UI or installs here.
                AppUpdateRepository.check(applicationContext)
                Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("TgWsProxy", "Background update state could not be saved", e)
            Result.retry()
        }
    }
}
