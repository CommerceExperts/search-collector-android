package io.searchhub.collector.workmanager

import android.content.Context
import androidx.work.*
import io.searchhub.collector.SearchCollector
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that flushes queued events in the background.
 * Guarantees event delivery even when the app is killed before the in-process auto-flush fires.
 *
 * Setup (call once on app start, after [SearchCollector.configure]):
 * ```kotlin
 * SearchCollectorFlushWorker.schedule(context)
 * ```
 */
class SearchCollectorFlushWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            SearchCollector.flush()
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val WORK_NAME = "SearchCollectorPeriodicFlush"

        /**
         * Schedule a periodic background flush.
         * Safe to call multiple times — uses KEEP policy to avoid duplicate workers.
         *
         * @param context Application context
         * @param intervalMinutes Minimum interval between flushes (default: 15 minutes, system minimum)
         * @param requireNetwork Only run when network is connected (default: true)
         */
        @JvmStatic
        @JvmOverloads
        fun schedule(
            context: Context,
            intervalMinutes: Long = 15L,
            requireNetwork: Boolean = true,
        ) {
            val constraints = Constraints.Builder()
                .apply {
                    if (requireNetwork) setRequiredNetworkType(NetworkType.CONNECTED)
                }
                .build()

            val request = PeriodicWorkRequestBuilder<SearchCollectorFlushWorker>(
                intervalMinutes, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Cancel the scheduled periodic flush worker. */
        @JvmStatic
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
