package com.hiennv.flutter_callkit_incoming

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class DeclineWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val callId = inputData.getString(KEY_CALL_ID).orEmpty()
        val baseUrl = inputData.getString(KEY_BASE_URL).orEmpty()

        if (callId.isBlank() || baseUrl.isBlank()) {
            Log.e(TAG, "Missing callId/baseUrl. callId=$callId baseUrl=$baseUrl")
            return@withContext Result.failure()
        }

        // POST {baseUrl}/calls/{id}/decline
        val cleanBase = baseUrl.trimEnd('/')
        val url = "$cleanBase/test-api/"
         Log.d(TAG, "🌍 Decline URL: $url")
        try {
            val client = OkHttpClient.Builder()
                .callTimeout(8, TimeUnit.SECONDS)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            val body = "".toRequestBody("application/json".toMediaType())

            val req = Request.Builder()
                .url(url)
                .get()     // ✅ GET method
                .build()

            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    Log.i(TAG, "✅ Decline notified. callId=$callId code=${res.code}")
                    return@withContext Result.success()
                }

                Log.e(TAG, "❌ Decline failed. callId=$callId code=${res.code}")
                // Retry for temporary server/network issues
                if (runAttemptCount < 1) {
                   Log.d(TAG, "🔁 Scheduling one retry...")
                  return@withContext Result.retry()
                }

               Log.e(TAG, "⛔ Retry limit reached. Stopping.")
               return@withContext Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Decline exception. callId=$callId", e)
            if (runAttemptCount < 1) {
                Log.d(TAG, "🔁 Exception retry...")
                return@withContext Result.retry()
            }

            return@withContext Result.failure()
        }
    }

    companion object {
        private const val TAG = "CallkitDeclineWorker"
        private const val KEY_CALL_ID = "call_id"
        private const val KEY_BASE_URL = "base_url"

        fun enqueue(context: Context, callId: String, baseUrl: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val data = workDataOf(
                KEY_CALL_ID to callId,
                KEY_BASE_URL to baseUrl
            )

            val req = OneTimeWorkRequestBuilder<DeclineWorker>()
                .setConstraints(constraints)
                .setInputData(data)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    5, TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "callkit_decline_$callId",
                ExistingWorkPolicy.KEEP,
                req
            )
        }
    }
}
