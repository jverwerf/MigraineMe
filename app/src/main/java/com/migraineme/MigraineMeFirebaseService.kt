package com.migraineme

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull

/**
 * Firebase Cloud Messaging Service
 *
 * Handles incoming FCM messages to trigger background syncs.
 *
 * Supported message types:
 * - sync_location: Triggers location sync
 * - sync_screen_time: Triggers screen time sync
 * - sync_hourly: Triggers location, screen time, phone sleep, phone behavior, and Health Connect
 * - sync_health_connect: Triggers Health Connect data sync
 * - evening_checkin: Shows notification prompting the user to do their evening check-in
 * - recalibration_ready: Sets flag so HomeScreen shows the recalibration banner
 */
class MigraineMeFirebaseService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: ${token.take(20)}...")

        // Save to Supabase
        CoroutineScope(Dispatchers.IO).launch {
            saveFcmTokenToSupabase(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "FCM message received: ${message.data}")

        val type = message.data["type"] ?: return

        when (type) {
            "sync_location" -> {
                Log.d(TAG, "Triggering location sync from FCM")
                LocationDailySyncWorker.runOnceNow(applicationContext)
            }
            "sync_screen_time" -> {
                Log.d(TAG, "Triggering screen time sync from FCM")
                ScreenTimeSyncWorker.runOnce(applicationContext)
            }
            "sync_hourly" -> {
                // Hourly sync - triggers location, screen time, phone sleep, phone behavior, AND Health Connect
                Log.d(TAG, "Triggering hourly sync from FCM")
                LocationDailySyncWorker.runOnceNow(applicationContext)
                ScreenTimeSyncWorker.runOnce(applicationContext)
                PhoneSleepSyncWorker.runOnce(applicationContext)
                PhoneBehaviorSyncWorker.runOnce(applicationContext)
                triggerHealthConnectSync()
            }
            "sync_health_connect" -> {
                // Health Connect sync - triggers both changes worker and push worker
                Log.d(TAG, "Triggering Health Connect sync from FCM")
                triggerHealthConnectSync()
            }
            "evening_checkin" -> {
                Log.d(TAG, "Showing evening check-in notification")
                showEveningCheckinNotification()
            }
            "recalibration_ready" -> {
                Log.d(TAG, "Recalibration proposals ready — setting flag")
                applicationContext
                    .getSharedPreferences("recalibration", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("has_proposals", true)
                    .apply()
            }
            else -> {
                Log.w(TAG, "Unknown FCM message type: $type")
            }
        }
    }

    /**
     * Triggers Health Connect data sync:
     * 1. HealthConnectChangesWorker - reads changes from Health Connect → local outbox
     * 2. HealthConnectPushWorker - pushes from outbox → Supabase
     *
     * Uses OneTimeWorkRequest - no periodic scheduling needed since FCM controls timing.
     */
    private fun triggerHealthConnectSync() {
        try {
            val workManager = androidx.work.WorkManager.getInstance(applicationContext)

            // Run changes worker (reads from Health Connect)
            workManager.enqueue(
                androidx.work.OneTimeWorkRequestBuilder<HealthConnectChangesWorker>()
                    .build()
            )

            // Run push worker (pushes to Supabase)
            workManager.enqueue(
                androidx.work.OneTimeWorkRequestBuilder<HealthConnectPushWorker>()
                    .build()
            )

            Log.d(TAG, "Health Connect sync workers triggered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger Health Connect sync", e)
        }
    }

    private fun showEveningCheckinNotification() {
        val channelId = "evening_checkin"
        val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        // Create channel (no-op if already exists)
        val channel = android.app.NotificationChannel(
            channelId, "Evening Check-in",
            android.app.NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Daily evening check-in reminder" }
        nm.createNotificationChannel(channel)

        // Tap opens the check-in screen
        val intent = android.content.Intent(this, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", Routes.EVENING_CHECKIN)
        }
        val pi = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("How was today?")
            .setContentText("Take 15 seconds to log your day")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        nm.notify(8020, notification)
    }

    private suspend fun saveFcmTokenToSupabase(fcmToken: String) {
        try {
            val accessToken = SessionStore.getValidAccessToken(applicationContext)
            if (accessToken == null) {
                Log.w(TAG, "No access token - will save FCM token on next login")
                // Store locally for later
                SessionStore.saveFcmToken(applicationContext, fcmToken)
                return
            }

            val userId = SessionStore.readUserId(applicationContext)
            if (userId == null) {
                Log.w(TAG, "No user ID - will save FCM token on next login")
                SessionStore.saveFcmToken(applicationContext, fcmToken)
                return
            }

            // Save to Supabase profiles
            val client = okhttp3.OkHttpClient()
            val json = org.json.JSONObject().apply {
                put("fcm_token", fcmToken)
            }

            val request = okhttp3.Request.Builder()
                .url("${BuildConfig.SUPABASE_URL}/rest/v1/profiles?user_id=eq.$userId")
                .patch(okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    json.toString()
                ))
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Log.d(TAG, "FCM token saved to Supabase")
                SessionStore.saveFcmToken(applicationContext, fcmToken)
            } else {
                Log.e(TAG, "Failed to save FCM token: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving FCM token", e)
        }
    }

    companion object {
        private const val TAG = "FCMService"
    }
}

