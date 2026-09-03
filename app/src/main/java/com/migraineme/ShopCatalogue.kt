package com.migraineme

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The Shop catalogue, fetched rather than compiled in.
 *
 * Every card used to live in an array in this app, a second array in the iOS
 * app, and nowhere at all in VertigoMe and MeSeries, so changing a line of copy
 * or an affiliate link cost four code changes and a store release. The rows now
 * live in Postgres and `shop_catalogue(app, country, lang)` hands back exactly
 * the cards to draw, already translated and already pointed at the store that
 * serves the caller's country.
 *
 * Two things the server decides that this app deliberately does not:
 *  - Which store a card links to. Alpine runs separate programmes in the US and
 *    the EU, so the row carries the countries it serves.
 *  - Whether a card appears at all. No link row for the viewer's country means
 *    no card, which is how Alpine stays off a UK phone, where the programme
 *    rejected us and the sale would not track.
 *
 * The last good response is cached in SharedPreferences so the page still draws
 * on a plane, and so a cold open shows something before the network answers.
 */
object ShopCatalogue {
    private const val TAG = "ShopCatalogue"
    private const val PREFS = "shop_catalogue"
    private const val KEY_JSON = "rows"
    private const val KEY_STAMP = "fetched_at"
    private const val KEY_SCOPE = "scope"

    /** Product shots live in the public `shop` Storage bucket, not in the APK. */
    private const val BUCKET = "/storage/v1/object/public/shop/"

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    data class Item(
        val key: String,
        val name: String,
        val what: String,
        val evidence: String?,
        val pros: List<String>,
        val cons: List<String>,
        val rating: String?,
        val ratingSource: String?,
        val ratingUrl: String?,
        val iconKey: String,
        val photoUrl: String?,
        val prescriptionOnly: Boolean,
        val safetyNote: String?,
        val url: String,
        val code: String?,
        val note: String?
    )

    data class Group(val key: String, val title: String, val blurb: String, val items: List<Item>)

    /**
     * Country for the link lookup. The SIM's country beats the locale: someone
     * with an English phone living in Belgium should see the euro store.
     */
    private fun country(context: Context): String? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        val sim = tm?.simCountryIso?.takeIf { it.isNotBlank() }
        return (sim ?: Locale.getDefault().country.takeIf { it.isNotBlank() })?.uppercase(Locale.ROOT)
    }

    suspend fun load(context: Context): List<Group> = withContext(Dispatchers.IO) {
        val lang = LangPrefs.get().code
        val country = country(context)
        val scope = "$lang|$country"
        val fresh = fetch(context, country, lang)
        if (fresh != null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_JSON, fresh)
                .putString(KEY_SCOPE, scope)
                .putLong(KEY_STAMP, System.currentTimeMillis())
                .apply()
            return@withContext parse(fresh)
        }
        // Offline: the last good answer, but only if it was for this same
        // language and country. A cached Dutch catalogue on a phone that has
        // since moved to English would be worse than an empty page.
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_SCOPE, null) != scope) return@withContext emptyList()
        parse(prefs.getString(KEY_JSON, null) ?: return@withContext emptyList())
    }

    private fun fetch(context: Context, country: String?, lang: String): String? = try {
        val body = JSONObject().apply {
            put("p_app", "migraine")
            put("p_country", country ?: JSONObject.NULL)
            put("p_lang", lang)
        }
        val req = Request.Builder()
            .url("${BuildConfig.SUPABASE_URL}/rest/v1/rpc/shop_catalogue")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .apply {
                // Signed in or not, the catalogue is public: it is marketing
                // copy, and the page has to work before onboarding finishes.
                SessionStore.readAccessToken(context)?.let { addHeader("Authorization", "Bearer $it") }
            }
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                Log.w(TAG, "shop_catalogue ${res.code}")
                null
            } else res.body?.string()
        }
    } catch (e: Exception) {
        Log.w(TAG, "shop_catalogue failed: ${e.message}")
        null
    }

    /**
     * Impression and click log, our side of the partner panels' click counts.
     * `impression` is sent once per Shop open with every key the catalogue
     * returned; `click` is sent with the one key whose Buy row was tapped.
     * Rows land in `shop_events` via `record_shop_events` and the biz
     * dashboard reads them per card and app.
     *
     * Fire-and-forget on IO: the tap that opens the browser never waits on
     * it, and a failure is a lost data point, not a broken page.
     */
    fun recordEvents(context: Context, event: String, keys: List<String>) {
        if (keys.isEmpty()) return
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    put("p_app", "migraine")
                    put("p_platform", "android")
                    put("p_event", event)
                    put("p_item_keys", JSONArray(keys))
                    put("p_country", country(app) ?: JSONObject.NULL)
                }
                val req = Request.Builder()
                    .url("${BuildConfig.SUPABASE_URL}/rest/v1/rpc/record_shop_events")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .apply {
                        // With a session the row carries the user; without
                        // one it is still a counted anonymous impression.
                        SessionStore.readAccessToken(app)?.let { addHeader("Authorization", "Bearer $it") }
                    }
                    .build()
                http.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) Log.w(TAG, "record_shop_events ${res.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "record_shop_events failed: ${e.message}")
            }
        }
    }

    private fun parse(json: String): List<Group> = try {
        val arr = JSONArray(json)
        val groups = LinkedHashMap<String, MutableList<Item>>()
        val titles = LinkedHashMap<String, Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val gk = o.getString("group_key")
            titles.getOrPut(gk) { o.getString("group_title") to o.getString("group_blurb") }
            groups.getOrPut(gk) { mutableListOf() }.add(
                Item(
                    key = o.getString("item_key"),
                    name = o.getString("name"),
                    what = o.getString("what"),
                    evidence = o.optStringOrNull("evidence"),
                    pros = o.optStringList("pros"),
                    cons = o.optStringList("cons"),
                    rating = o.optStringOrNull("rating"),
                    ratingSource = o.optStringOrNull("rating_source"),
                    ratingUrl = o.optStringOrNull("rating_url"),
                    iconKey = o.getString("icon_key"),
                    photoUrl = o.optStringOrNull("photo_path")
                        ?.let { "${BuildConfig.SUPABASE_URL}$BUCKET$it" },
                    prescriptionOnly = o.optString("access") == "prescription",
                    safetyNote = o.optStringOrNull("safety_note"),
                    url = o.getString("url"),
                    code = o.optStringOrNull("code"),
                    note = o.optStringOrNull("note")
                )
            )
        }
        titles.map { (key, t) -> Group(key, t.first, t.second, groups[key].orEmpty()) }
    } catch (e: Exception) {
        Log.w(TAG, "shop_catalogue parse failed: ${e.message}")
        emptyList()
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private fun JSONObject.optStringList(name: String): List<String> {
        if (isNull(name)) return emptyList()
        val a = optJSONArray(name) ?: return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }
}
