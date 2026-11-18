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
import kr.dhl.studyapp.update.UpdateState
import kr.dhl.studyapp.update.Updater
import java.util.concurrent.TimeUnit

/** Background sync, which also reschedules alarms. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = Repo(applicationContext)
        val ok = runCatching { repo.sync() }.isSuccess
        notifyUpdate(repo)
        return if (ok) Result.success() else Result.retry()
    }

    /** Posts one notification per new build. */
    private suspend fun notifyUpdate(repo: Repo) {
        Updater.check(applicationContext, repo.prefs.githubToken)
        val s = Updater.state.value
        if (s is UpdateState.Available && s.build > repo.prefs.notifiedBuild) {
            repo.prefs.notifiedBuild = s.build
            Notify.update(applicationContext, s.build)
        }
    }

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
