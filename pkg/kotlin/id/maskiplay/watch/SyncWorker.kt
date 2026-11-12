package id.maskiplay.watch

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Periodic local library maintenance; no account or API key is needed. */
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        val cutoff = System.currentTimeMillis() - HISTORY_RETENTION_MS
        WatchDb.get(applicationContext).watchDao().pruneHistory(cutoff)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val UNIQUE_WORK = "maskiplay-watch-library-sync"
        private const val HISTORY_RETENTION_MS = 90L * 24 * 60 * 60 * 1000

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
