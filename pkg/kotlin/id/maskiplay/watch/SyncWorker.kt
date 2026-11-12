package id.maskiplay.watch

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Refreshes the public Melolo catalogue in the background and prunes old history.
 * Episode directories are fetched live when a title is opened, so new episodes
 * are not frozen into this catalogue cache.
 */
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        val app = applicationContext
        val cutoff = System.currentTimeMillis() - HISTORY_RETENTION_MS
        WatchDb.get(app).watchDao().pruneHistory(cutoff)

        val fresh = MeloloProvider().searchPopular()
        if (fresh.isNotEmpty()) CatalogCache.savePopular(app, fresh)

        if (fresh.isNotEmpty() || CatalogCache.loadPopular(app).isNotEmpty()) {
            Result.success()
        } else {
            Result.retry()
        }
    } catch (_: Exception) {
        if (CatalogCache.loadPopular(applicationContext).isNotEmpty()) Result.success()
        else Result.retry()
    }

    companion object {
        private const val UNIQUE_WORK = "maskiplay-watch-library-sync"
        private const val HISTORY_RETENTION_MS = 90L * 24 * 60 * 60 * 1000

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
