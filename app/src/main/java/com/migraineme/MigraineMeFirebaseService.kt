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
 * - new_insight: New daily recommendations ready — shows a notification and
 *   sets a flag so the Insights tab shows a "new" indicator
 * - daily_gauge: Today's risk gauge reached the user's alert threshold (or climbed
 *   to a higher zone since the last alert) — shows a notification naming the zone
 * - ongoing_migraine: A logged migraine still has no end time — nudges the user
 *   to go back and close it
 * - trigger_alert: A trigger or prodrome the user follows auto-fired for today
 *   (threshold or 2SD from baseline) — shows a notification naming the item
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
                Log.d(TAG, "Recalibration proposals ready — setting flag + showing notification")
                applicationContext
                    .getSharedPreferences("recalibration", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("has_proposals", true)
                    .apply()
                showRecalibrationNotification(message.data["count"]?.toIntOrNull())
            }
            "new_insight" -> {
                Log.d(TAG, "New daily insight ready — setting flag + showing notification")
                applicationContext
                    .getSharedPreferences("insights", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("has_new_insight", true)
                    .apply()
                showNewInsightNotification()
            }
            "ongoing_migraine" -> {
                Log.d(TAG, "Ongoing migraine reminder")
                showOngoingMigraineNotification(
                    daysOpen = message.data["days_open"]?.toIntOrNull(),
                    finalReminder = message.data["final_reminder"] == "1"
                )
            }
            "daily_gauge" -> {
                val zone = message.data["zone"] ?: return
                Log.d(TAG, "Gauge alert: zone=$zone")
                showGaugeNotification(
                    zone = zone,
                    percent = message.data["percent"]?.toIntOrNull(),
                    topTrigger = message.data["top_trigger"].orEmpty(),
                    escalated = message.data["escalated"] == "1"
                )
            }
            "trigger_alert" -> {
                val label = message.data["label"].orEmpty()
                Log.d(TAG, "Trigger alert: label=$label")
                showTriggerAlertNotification(
                    label = label,
                    notes = message.data["notes"].orEmpty(),
                    itemType = message.data["item_type"].orEmpty()
                )
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
            channelId, tSync("Evening Check-in"),
            android.app.NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = tSync("Daily evening check-in reminder") }
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
            .setContentTitle(tSync("How was today?"))
            .setContentText(tSync("Take 15 seconds to log your day"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        nm.notify(8020, notification)
    }

    private fun showNewInsightNotification() {
        val channelId = "new_insight"
        val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        // Create channel (no-op if already exists)
        val channel = android.app.NotificationChannel(
            channelId, tSync("New Insights"),
            android.app.NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = tSync("Alerts when new insights and recommendations are ready") }
        nm.createNotificationChannel(channel)

        // Tap opens the Insights tab
        val intent = android.content.Intent(this, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", Routes.INSIGHTS)
        }
        val pi = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tSync("New recommendations ready"))
            .setContentText(tSync("Your recommendations for today are ready. Tap to see them."))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        nm.notify(8021, notification)
    }

    /**
     * The weekly recalibration found material changes and wrote a new batch of
     * proposals. Until now this only set the SharedPreferences flag, so the
     * in-app banner was the sole signal and the push was invisible.
     */
    private fun showRecalibrationNotification(count: Int?) {
        val channelId = "recalibration_ready"
        val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        // Create channel (no-op if already exists)
        val channel = android.app.NotificationChannel(
            channelId, tSync("Profile Recalibration"),
            android.app.NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = tSync("Alerts when the app has learned something new about your migraines") }
        nm.createNotificationChannel(channel)

        // Tap opens the review screen where the proposals are accepted or rejected
        val intent = android.content.Intent(this, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", Routes.RECALIBRATION_REVIEW)
        }
        val pi = android.app.PendingIntent.getActivity(
            this, 2, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val body = if (count != null && count > 0) {
            tSync("%1\$s suggested changes to your profile. Tap to review.").replace("%1\$s", count.toString())
        } else {
            tSync("We have suggested changes to your profile. Tap to review.")
        }

        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tSync("Your profile has learned something new"))
            .setContentText(body)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        nm.notify(8025, notification)
    }

    /**
     * Today's risk gauge crossed the user's alert threshold.
     *
     * Fires at most once per zone per day, so a day can produce a "Mild" alert
     * in the morning and a "High" one later if things escalate. The zone and
     * percent come from the push payload so the notification can state the real
     * number rather than a vague "your gauge is ready".
     */
    private fun showGaugeNotification(
        zone: String,
        percent: Int?,
        topTrigger: String,
        escalated: Boolean
    ) {
        val channelId = "daily_gauge"
        val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        // Create channel (no-op if already exists)
        val channel = android.app.NotificationChannel(
            channelId, tSync("Daily Risk Gauge"),
            android.app.NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = tSync("Alerts when today's migraine risk reaches your chosen level") }
        nm.createNotificationChannel(channel)

        // Tap opens Home, where the gauge lives
        val intent = android.content.Intent(this, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", Routes.HOME)
        }
        val pi = android.app.PendingIntent.getActivity(
            this, 1, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val zoneLabel = when (zone.uppercase()) {
            "HIGH" -> "High"
            "MILD" -> "Mild"
            "LOW" -> "Low"
            else -> return  // NONE never alerts
        }

        val title = if (escalated) tSync("Risk just went up: %s", zoneLabel) else tSync("Today's risk: %s", zoneLabel)
        val body = buildString {
            if (percent != null) append("Your gauge is at $percent%. ")
            if (topTrigger.isNotBlank()) append("Biggest factor: $topTrigger. ")
            append("Tap to see what's driving it.")
        }

        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        nm.notify(8022, notification)
    }

    /**
     * A logged migraine still has no end time.
     *
     * Repeats on the user's chosen interval until they close it, then stops
     * after a few unanswered nudges (the backend decides that and flags the
     * last one, so the copy can sign off rather than trail away).
     */
    private fun showOngoingMigraineNotification(daysOpen: Int?, finalReminder: Boolean) {
        val channelId = "ongoing_migraine"
        val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        // Create channel (no-op if already exists)
        val channel = android.app.NotificationChannel(
            channelId, tSync("Ongoing Migraine"),
            android.app.NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = tSync("Reminders to close a migraine you logged but never ended") }
        nm.createNotificationChannel(channel)

        // Tap opens the journal, where the open migraine can be edited
        val intent = android.content.Intent(this, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", Routes.JOURNAL)
        }
        val pi = android.app.PendingIntent.getActivity(
            this, 2, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val body = buildString {
            if (daysOpen != null) {
                if (daysOpen == 1) append(tSync("You logged a migraine 1 day ago and it's still open. "))
                else append(tSync("You logged a migraine %s days ago and it's still open. ", daysOpen))
            } else {
                append(tSync("You have a migraine that's still open. "))
            }
            append(
                if (finalReminder) tSync("Tap to add how it ended. We won't ask about this one again.")
                else tSync("Tap to add how it ended.")
            )
        }

        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tSync("Is your migraine over?"))
            .setContentText(body)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        nm.notify(8023, notification)
    }

    /**
     * A trigger or prodrome the user opted into alerts for auto-fired today
     * (threshold crossed or 2SD from baseline).
     *
     * The backend sends at most one per item per local day. The notes line
     * carries the real numbers (e.g. "Sleep duration: 5.2h — 2SD below avg
     * 7.4h") so the notification can say why rather than a vague "went off".
     */
    private fun showTriggerAlertNotification(label: String, notes: String, itemType: String) {
        val channelId = "trigger_alert"
        val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        // Create channel (no-op if already exists)
        val channel = android.app.NotificationChannel(
            channelId, tSync("Trigger alerts"),
            android.app.NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = tSync("Alerts when an item you follow becomes a trigger for the day") }
        nm.createNotificationChannel(channel)

        // Tap opens the journal, where the fired item shows on today's log
        val intent = android.content.Intent(this, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", Routes.JOURNAL)
        }
        val pi = android.app.PendingIntent.getActivity(
            this, 3, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (itemType == "prodrome") tSync("Early warning detected") else tSync("Trigger detected")
        val body = if (notes.isNotBlank()) notes else label

        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        nm.notify(8024, notification)
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

