package com.fieldrepository.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class NotificationSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val tokenStore = TokenStore(context)
        if (tokenStore.getToken().isNullOrBlank()) return Result.success()
        if (com.fieldrepository.app.ui.mustChangePasswordBlocks(tokenStore.getUser())) return Result.success()
        val repository = FieldRepository(ApiClient.create(tokenStore), tokenStore)
        runCatching { repository.syncNotifications(context) }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "notification-sync"

        fun schedule(context: Context) {
            runCatching {
                val request = PeriodicWorkRequestBuilder<NotificationSyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }
    }
}
