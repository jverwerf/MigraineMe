package com.migraineme

import android.content.Context
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.net.URLDecoder

/**
 * Did this install come from a Google Ads click?
 *
 * Play remembers the link that led to the install (the "install referrer") for
 * ~90 days. We ask it once, on the first app start, keep the answer locally,
 * and once the user has an account we write it to `install_attribution` so the
 * dashboard can count ad installs that became signups / payers. One row per
 * user, written once; a later sign-in on the same device does not rewrite it.
 *
 * Classification (all from the referrer string Play hands back):
 *   google_ads : has gclid / gbraid / wbraid, or utm_medium is cpc/ppc/ads
 *   organic    : the Play default "utm_source=google-play&utm_medium=organic"
 *   unknown    : anything else (empty, other campaign links, referrer API error)
 */
object InstallAttribution {
    private const val TAG = "InstallAttribution"
    private const val PREFS = "install_attribution_prefs"
    private const val K_CAPTURED = "captured"
    private const val K_REFERRER = "referrer"
    private const val K_INSTALL_TS = "install_ts"
    private const val K_SYNCED_USER = "synced_user"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Call from Application.onCreate. Runs the Play round trip once per install. */
    fun captureOnce(context: Context) {
        val appCtx = context.applicationContext
        if (prefs(appCtx).getBoolean(K_CAPTURED, false)) return
        val client = InstallReferrerClient.newBuilder(appCtx).build()
        try {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(code: Int) {
                    try {
                        if (code == InstallReferrerClient.InstallReferrerResponse.OK) {
                            val d = client.installReferrer
                            prefs(appCtx).edit()
                                .putString(K_REFERRER, d.installReferrer ?: "")
                                .putLong(K_INSTALL_TS, d.installBeginTimestampSeconds)
                                .putBoolean(K_CAPTURED, true)
                                .apply()
                        } else {
                            // FEATURE_NOT_SUPPORTED / SERVICE_UNAVAILABLE: no Play, sideload, etc.
                            prefs(appCtx).edit()
                                .putString(K_REFERRER, "error:$code")
                                .putBoolean(K_CAPTURED, true)
                                .apply()
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "referrer read failed", t)
                    } finally {
                        runCatching { client.endConnection() }
                    }
                }

                override fun onInstallReferrerServiceDisconnected() { /* one shot; retry next launch */ }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "referrer connect failed", t)
        }
    }

    private fun params(referrer: String): Map<String, String> =
        referrer.split('&').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else runCatching {
                URLDecoder.decode(kv.substring(0, i), "UTF-8").lowercase() to
                    URLDecoder.decode(kv.substring(i + 1), "UTF-8")
            }.getOrNull()
        }.toMap()

    @Serializable
    private data class Row(
        val user_id: String,
        val platform: String,
        val source: String,
        val campaign_id: String? = null,
        val click_id: String? = null,
        val raw: String? = null,
        val install_at: String? = null,
    )

    /** Call once a session exists (after ensureProfile). Non-fatal, idempotent. */
    suspend fun syncAfterSignIn(context: Context) {
        val appCtx = context.applicationContext
        val p = prefs(appCtx)
        if (!p.getBoolean(K_CAPTURED, false)) return
        val token = SessionStore.getValidAccessToken(appCtx) ?: return
        val userId = SessionStore.readUserId(appCtx)
            ?: JwtUtils.extractUserIdFromAccessToken(token) ?: return
        if (p.getString(K_SYNCED_USER, null) == userId) return

        val raw = p.getString(K_REFERRER, "") ?: ""
        val q = params(raw)
        val click = q["gclid"] ?: q["gbraid"] ?: q["wbraid"]
        val medium = q["utm_medium"]?.lowercase()
        val source = when {
            click != null || medium in setOf("cpc", "ppc", "ads", "paid") -> "google_ads"
            q["utm_source"] == "google-play" && medium == "organic" -> "organic"
            else -> "unknown"
        }
        val installTs = p.getLong(K_INSTALL_TS, 0L)
        val row = Row(
            user_id = userId,
            platform = "android", // explicit: the shared Json has encodeDefaults=false
            source = source,
            campaign_id = q["utm_campaign"],
            click_id = click,
            raw = raw.take(2000),
            install_at = if (installTs > 0) java.time.Instant.ofEpochSecond(installTs).toString() else null,
        )
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val base = BuildConfig.SUPABASE_URL.trimEnd('/')
                val resp: HttpResponse = SupabaseProfileService.httpClient.post("$base/rest/v1/install_attribution") {
                    header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    header("Authorization", "Bearer $token")
                    // First write wins: a re-login never rewrites the install record.
                    header("Prefer", "resolution=ignore-duplicates,return=minimal")
                    contentType(ContentType.Application.Json)
                    setBody(row)
                }
                resp.status.value in 200..299
            }.getOrElse { Log.w(TAG, "sync failed (non-blocking)", it); false }
        }
        if (ok) p.edit().putString(K_SYNCED_USER, userId).apply()
    }
}
