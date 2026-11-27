package dev.appolon.memoswear.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.appolon.memoswear.MemosWearApplication
import java.util.concurrent.TimeUnit

/** Sends the changes queued on the watch. Runs when there is a connection and backs off while offline. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as MemosWearApplication).container.repository
        return when (repository.syncPending()) {
            MemoRepository.SyncResult.SUCCESS -> Result.success()
            MemoRepository.SyncResult.RETRY -> Result.retry()
            MemoRepository.SyncResult.AUTH_FAILED -> Result.failure()
        }
    }

    companion object {
        private const val SYNC_WORK = "memoswear-sync"
        private const val REFRESH_WORK = "memoswear-refresh"
        private const val RETRY_DELAY_SECONDS = 20L

        private val online = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Each call is appended after the pending ones, so nothing is dropped and order is kept. */
        fun scheduleSync(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(SYNC_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /** Reloads the newest memos once, for tiles shown while the app is closed. */
        fun scheduleRefresh(context: Context) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(online)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(REFRESH_WORK, ExistingWorkPolicy.KEEP, request)
        }
    }
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MemosWearApplication).container
        if (!container.repository.awaitSession()) return Result.success()
        container.repository.refresh(force = true)
        container.refreshTiles()
        return Result.success()
    }
}
