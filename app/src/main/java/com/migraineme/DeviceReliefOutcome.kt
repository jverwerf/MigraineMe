package com.migraineme

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Neuromodulation devices the app knows about. Used to decide when a relief log
 * should get the 2-hour outcome follow-up, and to offer name chips when adding
 * a device treatment regimen.
 */
object DeviceCatalog {
    val LABELS = listOf(
        "CEFALY", "Nerivio", "gammaCore", "sTMS (SAVI Dual)", "Relivion",
        "Quell", "Reliefband", "CalmiGo", "HeartMath",
        "Avulux glasses", "TheraSpecs", "Allay Lamp", "ThermaZone", "Apollo Neuro"
    )

    private val MATCHERS = listOf(
        "cefaly", "nerivio", "gammacore", "stms", "savi", "relivion",
        "quell", "reliefband", "reletex", "calmigo", "heartmath", "inner balance",
        "avulux", "theraspecs", "allay", "thermazone", "apollo"
    )

    /**
     * [category] is the authoritative answer and is checked first: it is the
     * pool item's own user_reliefs.category, which is "Device" for every device
     * in the library and for anything the user filed as one, whatever they
     * named it.
     *
     * [label] matching stays as the FALLBACK, and has to. Reliefs logged before
     * the app started stamping category have it NULL, and a few paths write a
     * generic label that belongs to no pool item at all. For those the name is
     * the only signal there is.
     */
    fun isDeviceRelief(label: String?, category: String? = null): Boolean {
        if (category?.trim()?.equals("Device", ignoreCase = true) == true) return true
        val l = label?.lowercase() ?: return false
        return MATCHERS.any { l.contains(it) }
    }
}

/**
 * Posts a "did it help?" notification 2 hours after a device relief session is
 * logged. The three actions patch reliefs.relief_scale in place, matching the
 * clinical acute endpoint (pain free at 2h) without opening the app.
 */
class DeviceReliefOutcomeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val reliefId = inputData.getString(KEY_RELIEF_ID) ?: return Result.success()
        val label = inputData.getString(KEY_LABEL) ?: tSync("your device")

        val optedIn = runCatching {
            EdgeFunctionsService().getNotificationEnabled(ctx, "device_relief_outcome", default = true)
        }.getOrDefault(true)
        if (!optedIn) return Result.success()

        // If the user already recorded an outcome (e.g. edited the log), stay quiet.
        val alreadyScored = runCatching {
            val token = SessionStore.getValidAccessToken(ctx) ?: return Result.success()
            val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
            val row = db.getReliefById(token, reliefId)
            (row.reliefScale ?: "NONE") != "NONE"
        }.getOrDefault(false)
        if (alreadyScored) return Result.success()

        postNotification(ctx, reliefId, label)
        return Result.success()
    }

    private fun postNotification(context: Context, reliefId: String, label: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, tSync("Device check-ins"), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val notifId = notifIdFor(reliefId)

        fun action(scale: String, title: String, requestCode: Int): NotificationCompat.Action {
            val intent = Intent(context, DeviceReliefOutcomeReceiver::class.java).apply {
                putExtra(KEY_RELIEF_ID, reliefId)
                putExtra(KEY_SCALE, scale)
                putExtra(KEY_NOTIF_ID, notifId)
            }
            val pi = PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Action.Builder(0, title, pi).build()
        }

        val openApp = PendingIntent.getActivity(
            context, notifId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("navigate_to", Routes.JOURNAL)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tSync("How did %s go?", label))
            .setContentText(tSync("It's been 2 hours since your session. Recording the outcome sharpens your Insights."))
            .setStyle(NotificationCompat.BigTextStyle().bigText(tSync("It's been 2 hours since your %s session. Recording the outcome sharpens your Insights and your provider report.", label)))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .addAction(action("HIGH", tSync("Pain free"), notifId * 10 + 1))
            .addAction(action("MILD", tSync("Better"), notifId * 10 + 2))
            .addAction(action("NONE", tSync("No change"), notifId * 10 + 3))
            .build()

        NotificationManagerCompat.from(context).notify(notifId, notif)
    }

    companion object {
        const val KEY_RELIEF_ID = "relief_id"
        const val KEY_LABEL = "label"
        const val KEY_SCALE = "scale"
        const val KEY_NOTIF_ID = "notif_id"
        const val CHANNEL_ID = "device_relief_outcome"

        private fun notifIdFor(reliefId: String): Int = 92000 + (reliefId.hashCode() and 0x3FF)

        /** Unique work name for one relief's follow-up. */
        private fun workNameFor(reliefId: String) = "device_relief_outcome_$reliefId"

        /**
         * Call after inserting a relief log. No-op unless the relief is a
         * device.
         *
         * Pass the inserted row's own [category] (ReliefRow.category), not a
         * guess: that is the pool item's category as it was actually stored, so
         * a device the user renamed or one that is not in DeviceCatalog still
         * gets its follow-up. Only leave it null where there genuinely is no
         * category, and accept name matching there.
         */
        fun scheduleIfDevice(context: Context, reliefId: String, label: String?, category: String? = null) {
            if (!DeviceCatalog.isDeviceRelief(label, category)) return
            val req = OneTimeWorkRequestBuilder<DeviceReliefOutcomeWorker>()
                .setInitialDelay(2, TimeUnit.HOURS)
                .setInputData(workDataOf(KEY_RELIEF_ID to reliefId, KEY_LABEL to (label ?: "your device")))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                workNameFor(reliefId),
                ExistingWorkPolicy.REPLACE,
                req
            )
        }

        /**
         * Call when a relief log is deleted. Drops the pending follow-up and
         * clears the notification if it has already been posted, so a relief
         * that no longer exists can never ask the user how it went.
         *
         * Safe to call for any relief: the work name is derived from the id, so
         * cancelling one that was never scheduled is a no-op. Call sites do not
         * have to re-check DeviceCatalog — and must not, since a relief deleted
         * after its label was edited would otherwise keep its follow-up.
         */
        fun cancel(context: Context, reliefId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workNameFor(reliefId))
            NotificationManagerCompat.from(context).cancel(notifIdFor(reliefId))
        }
    }
}

/** Handles the notification action taps: patches relief_scale, then dismisses. */
class DeviceReliefOutcomeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reliefId = intent.getStringExtra(DeviceReliefOutcomeWorker.KEY_RELIEF_ID) ?: return
        val scale = intent.getStringExtra(DeviceReliefOutcomeWorker.KEY_SCALE) ?: return
        val notifId = intent.getIntExtra(DeviceReliefOutcomeWorker.KEY_NOTIF_ID, -1)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val token = SessionStore.getValidAccessToken(context) ?: return@launch
                val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                db.updateRelief(accessToken = token, id = reliefId, reliefScale = scale)
            } catch (_: Throwable) {
                // Best effort; the user can still set the outcome by editing the log.
            } finally {
                if (notifId != -1) NotificationManagerCompat.from(context).cancel(notifId)
                pending.finish()
            }
        }
    }
}
