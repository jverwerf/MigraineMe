package com.migraineme

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin ViewModel for the recalibration review screen.
 *
 * All computation happens server-side. This just:
 * 1. Reads proposals from recalibration_proposals
 * 2. Shows them for review
 * 3. Sends accept/reject to apply-recalibration edge function
 */
class RecalibrationViewModel : ViewModel() {

    private val TAG = "RecalibVM"

    data class Proposal(
        val id: String,
        val type: String,       // trigger, prodrome, medicine, relief, symptom, activity,
                                // missed_activity, gauge_threshold, gauge_decay, profile
        val label: String,
        val fromValue: String?,
        val toValue: String?,
        val shouldFavorite: Boolean,
        val reasoning: String?,
        var accepted: Boolean = true,   // default: accept all
        /** Row metadata as stored; for type "answer": entry, question, options, kind. */
        val metadata: JSONObject = JSONObject(),
        /**
         * type "answer" only: the value the user picked instead of the suggested
         * one (null = take `toValue` as proposed). Sent as {id, to_value}.
         */
        val chosenValue: String? = null,
    ) {
        val options: List<String>
            get() = metadata.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        val question: String? get() = metadata.optString("question", "").takeIf { it.isNotBlank() }
        val kind: String get() = metadata.optString("kind", "single")
        /** What apply will write if accepted. */
        val effectiveValue: String? get() = chosenValue ?: toValue
    }

    data class RecalibrationState(
        val loading: Boolean = true,
        val proposals: List<Proposal> = emptyList(),
        val clinicalAssessment: String = "",
        val calibrationNotes: String = "",
        val call1Summary: String = "",
        val call2Summary: String = "",
        val applying: Boolean = false,
        val applied: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(RecalibrationState())
    val state: StateFlow<RecalibrationState> = _state

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // ═══════════════════════════════════════════════════════════════
    // Load pending proposals
    // ═══════════════════════════════════════════════════════════════

    fun loadProposals(context: Context) {
        viewModelScope.launch {
            _state.value = RecalibrationState(loading = true)
            try {
                val result = withContext(Dispatchers.IO) { fetchProposals(context) }
                _state.value = result
            } catch (e: Exception) {
                Log.e(TAG, "loadProposals error", e)
                _state.value = RecalibrationState(loading = false, error = e.message)
            }
        }
    }

    private suspend fun fetchProposals(context: Context): RecalibrationState {
        val accessToken = SessionStore.getValidAccessToken(context.applicationContext)
            ?: return RecalibrationState(loading = false, error = tSync("Not authenticated"))

        val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/recalibration_proposals" +
                "?status=eq.pending&order=created_at.desc" +
                "&select=id,type,label,from_value,to_value,should_favorite,reasoning,metadata"

        val request = Request.Builder().url(url).get()
            .header("Authorization", "Bearer $accessToken")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return RecalibrationState(loading = false, error = tSync("Failed to load proposals"))
            }

            val arr = JSONArray(response.body?.string() ?: "[]")
            var clinicalAssessment = ""
            var calibrationNotes = ""
            var call1Summary = ""
            var call2Summary = ""
            val proposals = mutableListOf<Proposal>()

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val type = obj.getString("type")
                val label = obj.getString("label")

                // Extract summary metadata
                if (type == "summary" && label == "_meta") {
                    clinicalAssessment = obj.optString("reasoning", "")
                    val meta = try {
                        // PostgREST returns JSONB as a JSON object, not a string
                        val rawMeta = obj.opt("metadata")
                        when (rawMeta) {
                            is JSONObject -> rawMeta
                            is String -> JSONObject(rawMeta)
                            else -> JSONObject()
                        }
                    } catch (_: Exception) { JSONObject() }
                    // optString turns a JSON null into the string "null", which then
                    // rendered as a card saying "null" after a profile-only run
                    // (no Call 2, so call2_summary / calibration_notes are null).
                    fun metaText(key: String) = meta.optString(key, "").takeIf { it != "null" } ?: ""
                    call1Summary = metaText("call1_summary")
                    call2Summary = metaText("call2_summary")
                    calibrationNotes = metaText("calibration_notes")
                    continue
                }

                val rowMeta = try {
                    when (val raw = obj.opt("metadata")) {
                        is JSONObject -> raw
                        is String -> JSONObject(raw)
                        else -> JSONObject()
                    }
                } catch (_: Exception) { JSONObject() }
                proposals.add(Proposal(
                    id = obj.getString("id"),
                    type = type,
                    label = label,
                    fromValue = obj.optString("from_value", null)?.takeIf { it != "null" },
                    toValue = obj.optString("to_value", null)?.takeIf { it != "null" },
                    shouldFavorite = obj.optBoolean("should_favorite", false),
                    reasoning = obj.optString("reasoning", null)?.takeIf { it != "null" },
                    metadata = rowMeta,
                ))
            }

            return RecalibrationState(
                loading = false,
                proposals = proposals,
                clinicalAssessment = clinicalAssessment,
                calibrationNotes = calibrationNotes,
                call1Summary = call1Summary,
                call2Summary = call2Summary,
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Toggle individual proposals
    // ═══════════════════════════════════════════════════════════════

    /** type "answer": pick a value other than the suggested one; picking also accepts. */
    fun chooseAnswer(proposalId: String, value: String) {
        val current = _state.value
        _state.value = current.copy(
            proposals = current.proposals.map {
                if (it.id == proposalId) it.copy(chosenValue = value, accepted = true) else it
            }
        )
    }

    fun toggleProposal(proposalId: String) {
        val current = _state.value
        _state.value = current.copy(
            proposals = current.proposals.map {
                if (it.id == proposalId) it.copy(accepted = !it.accepted) else it
            }
        )
    }

    fun acceptAll() {
        val current = _state.value
        _state.value = current.copy(proposals = current.proposals.map { it.copy(accepted = true) })
    }

    fun rejectAll() {
        val current = _state.value
        _state.value = current.copy(proposals = current.proposals.map { it.copy(accepted = false) })
    }

    // ═══════════════════════════════════════════════════════════════
    // Apply decisions
    // ═══════════════════════════════════════════════════════════════

    fun applyDecisions(context: Context) {
        viewModelScope.launch {
            val current = _state.value
            _state.value = current.copy(applying = true)
            try {
                withContext(Dispatchers.IO) { callApply(context, current.proposals) }
                _state.value = current.copy(applying = false, applied = true)
            } catch (e: Exception) {
                Log.e(TAG, "apply error", e)
                _state.value = current.copy(applying = false, error = e.message)
            }
        }
    }

    private suspend fun callApply(context: Context, proposals: List<Proposal>) {
        val accessToken = SessionStore.getValidAccessToken(context.applicationContext)
            ?: throw Exception("Not authenticated")

        // An "answer" the user adjusted goes as {id, to_value}; apply validates the
        // override against the options and writes it back onto the proposal row.
        val body = JSONObject().apply {
            put("accepted", JSONArray(proposals.filter { it.accepted }.map { p ->
                val chosen = p.chosenValue
                if (p.type == "answer" && chosen != null && chosen != p.toValue)
                    JSONObject().put("id", p.id).put("to_value", chosen)
                else p.id
            }))
            put("rejected", JSONArray(proposals.filter { !it.accepted }.map { it.id }))
        }

        val request = Request.Builder()
            .url("${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/apply-recalibration")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $accessToken")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Apply failed: ${response.body?.string()}")
            }
            Log.d(TAG, "Recalibration applied: ${response.body?.string()}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // On-demand recalibration (user-initiated from settings)
    // ═══════════════════════════════════════════════════════════════

    fun requestRecalibration(context: Context) {
        viewModelScope.launch {
            _state.value = RecalibrationState(loading = true)
            try {
                withContext(Dispatchers.IO) {
                    val accessToken = SessionStore.getValidAccessToken(context.applicationContext)
                        ?: throw Exception("Not authenticated")

                    val request = Request.Builder()
                        .url("${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/recalibrate")
                        .post("""{"force": true}""".toRequestBody("application/json".toMediaType()))
                        .header("Authorization", "Bearer $accessToken")
                        .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                        .build()

                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string() ?: ""
                        Log.d(TAG, "Recalibration requested: $body")
                        if (!response.isSuccessful) {
                            throw Exception("Recalibration failed: $body")
                        }
                    }
                }
                // Reload proposals
                loadProposals(context)
            } catch (e: Exception) {
                Log.e(TAG, "requestRecalibration error", e)
                _state.value = RecalibrationState(loading = false, error = e.message)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Check for pending proposals (for HomeScreen banner)
    // ═══════════════════════════════════════════════════════════════

    companion object {
        suspend fun hasPendingProposals(context: Context): Boolean {
            val accessToken = SessionStore.getValidAccessToken(context.applicationContext)
                ?: return false
            val userId = SessionStore.readUserId(context.applicationContext)
                ?: JwtUtils.extractUserIdFromAccessToken(accessToken)
                ?: return false

            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/recalibration_proposals" +
                    "?user_id=eq.$userId&status=eq.pending&limit=1&select=id"

            val request = Request.Builder().url(url).get()
                .header("Authorization", "Bearer $accessToken")
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .build()

            return try {
                OkHttpClient().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return false
                    JSONArray(response.body?.string() ?: "[]").length() > 0
                }
            } catch (_: Exception) { false }
        }
    }
}
