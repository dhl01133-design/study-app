package kr.dhl.studyapp.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kr.dhl.studyapp.data.Repo
import java.util.concurrent.TimeUnit

/** Background sync, which also reschedules alarms. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result =
        runCatching { Repo(applicationContext).sync() }.fold({ Result.success() }, { Result.retry() })

    companion object {
        private val ONLINE = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Every 3 hours, so deadlines the midnight iCampus sweep adds get alarms the same day. */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(3, TimeUnit.HOURS).setConstraints(ONLINE).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun now(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(ONLINE).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
