// app/src/main/java/com/migraineme/CommunityViewModel.kt
package com.migraineme

import android.os.Build
import android.text.Html
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private const val TAG = "CommunityVM"

/** Decode HTML entities like &#8220; &amp; &#8217; etc. into plain text. */
private fun decodeHtml(text: String): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
        Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    else
        @Suppress("DEPRECATION") Html.fromHtml(text).toString().trim()

// -- Article models --

@Serializable
data class ArticleRow(
    val id: String,
    val title: String,
    val url: String,
    @SerialName("image_url") val imageUrl: String? = null,
    val source: String? = null,
    @SerialName("ai_summary") val aiSummary: String? = null,
    @SerialName("relevance_score") val relevanceScore: Int? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("article_tags") val articleTags: List<ArticleTagJoin>? = null
)

@Serializable
data class ArticleTagJoin(
    val confidence: Double? = null,
    val tags: TagRef? = null
)

@Serializable
data class TagRef(
    val id: String,
    val name: String,
    val category: String? = null
)

// -- Forum models --

@Serializable
data class ForumPostRow(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    val title: String,
    val body: String,
    val attachment: JsonObject? = null,
    @SerialName("reply_count") val replyCount: Int = 0,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String? = null,
    val profiles: ForumProfile? = null
)

@Serializable
data class ForumProfile(
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null
)

@Serializable
data class ForumCommentRow(
    val id: String,
    @SerialName("post_id") val postId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("parent_id") val parentId: String? = null,
    val body: String,
    val attachment: JsonObject? = null,
    @SerialName("created_at") val createdAt: String,
    val profiles: ForumProfile? = null
)

// -- Lightweight models for user data --

@Serializable
private data class UserTriggerSlim(
    val label: String,
    @SerialName("prediction_value") val predictionValue: String? = null
)

@Serializable
private data class UserProdromeSlim(
    val label: String,
    @SerialName("prediction_value") val predictionValue: String? = null
)

@Serializable
private data class UserMedicineSlim(val label: String)

@Serializable
private data class UserReliefSlim(val label: String)

@Serializable
private data class TriggerPrefSlim(
    val status: String,
    @SerialName("user_triggers") val trigger: TriggerPrefLabel? = null
)

@Serializable
private data class TriggerPrefLabel(val label: String? = null)

@Serializable
private data class ProdromePrefSlim(
    val status: String,
    @SerialName("user_prodromes") val prodrome: ProdromePrefLabel? = null
)

@Serializable
private data class ProdromePrefLabel(val label: String? = null)

@Serializable
private data class MedicinePrefSlim(
    val status: String,
    @SerialName("user_medicines") val medicine: MedicinePrefLabel? = null
)

@Serializable
private data class MedicinePrefLabel(val label: String? = null)

@Serializable
private data class ReliefPrefSlim(
    val status: String,
    @SerialName("user_reliefs") val relief: ReliefPrefLabel? = null
)

@Serializable
private data class ReliefPrefLabel(val label: String? = null)

@Serializable
private data class ArticleFavoriteRow(
    @SerialName("article_id") val articleId: String
)

@Serializable
private data class ForumFavoriteRow(
    @SerialName("post_id") val postId: String
)

@Serializable
private data class UnreadCountRow(
    val count: Int
)

@Serializable
private data class CommentCountRow(
    @SerialName("article_id") val articleId: String
)

@Serializable
private data class TagRow(
    val id: String,
    val name: String,
    val category: String? = null
)

// -- Me Too models --

@Serializable
private data class MeTooRow(
    @SerialName("post_id") val postId: String
)

@Serializable
private data class MeTooCountRow(
    @SerialName("post_id") val postId: String,
    @SerialName("me_too_count") val meTooCount: Int
)

// -- Thread summary model --

@Serializable
data class ThreadSummaryRow(
    @SerialName("post_id") val postId: String,
    val summary: String,
    @SerialName("comment_count") val commentCount: Int = 0,
    @SerialName("generated_at") val generatedAt: String? = null
)

// -- Discussion starter model --

data class DiscussionStarter(
    val triggerName: String,
    val communityCount: Int,       // how many other users share this trigger
    val pinnedTopicId: String?,    // deep-link to a relevant pinned topic
    val tagId: String?             // or deep-link to a filtered article view
)

// -- Notification model --

@Serializable
data class CommunityNotificationRow(
    val id: String,
    val type: String? = null,        // "article", "comment_reply", "forum_reply", "me_too"
    @SerialName("reference_id") val referenceId: String? = null,
    val title: String? = null,
    val body: String? = null,
    val read: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null
)

// -- State --

data class CommunityState(
    val articles: List<ArticleRow> = emptyList(),
    val userMatchingTagIds: Set<String> = emptySet(),
    val favoriteIds: Set<String> = emptySet(),
    val commentCounts: Map<String, Int> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
    val topTab: Int = 0,        // 0 = Articles, 1 = Forum
    val selectedTab: Int = 0,   // Article sub-tab: 0 = For You, 1 = Latest, 2 = Browse, 3 = Saved
    // Forum state
    val forumPosts: List<ForumPostRow> = emptyList(),
    val forumLoading: Boolean = false,
    val forumComments: List<ForumCommentRow> = emptyList(),
    val forumCommentsLoading: Boolean = false,
    val forumFavoriteIds: Set<String> = emptySet(),
    // Me Too state (shared across articles + forum — same table)
    val meTooCountMap: Map<String, Int> = emptyMap(),  // post_id or article_id -> count
    val myMeTooIds: Set<String> = emptySet(),          // IDs the current user has me-too'd
    // Thread/article summaries
    val threadSummaries: Map<String, String> = emptyMap(),  // post_id or article_id -> summary text
    // Discussion starters (computed from user profile)
    val discussionStarter: DiscussionStarter? = null,
    // Notifications
    val unreadCount: Int = 0,
    val notifications: List<CommunityNotificationRow> = emptyList(),
    // Moderation
    val showModerationAlert: Boolean = false
)

class CommunityViewModel : ViewModel() {

    private val _state = MutableStateFlow(CommunityState())
    val state: StateFlow<CommunityState> = _state

    private val supabaseUrl = BuildConfig.SUPABASE_URL
    private val supabaseKey = BuildConfig.SUPABASE_ANON_KEY

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
    }

    fun selectTopTab(tab: Int) {
        _state.value = _state.value.copy(topTab = tab)
    }

    fun dismissModerationAlert() {
        _state.value = _state.value.copy(showModerationAlert = false)
    }

    fun selectTab(tab: Int) {
        _state.value = _state.value.copy(selectedTab = tab)
    }

    fun loadAll(accessToken: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                // Fetch everything in parallel
                val articlesDeferred = async { fetchArticles(accessToken) }
                val favoritesDeferred = async { fetchArticleFavorites(accessToken) }
                val userLabelsDeferred = async { collectUserLabels(accessToken) }
                val allTagsDeferred = async { fetchAllTags(accessToken) }
                val commentCountsDeferred = async { fetchCommentCounts(accessToken) }
                val forumDeferred = async { fetchForumPosts(accessToken) }
                val forumFavDeferred = async { fetchForumFavorites(accessToken) }
                val unreadDeferred = async { fetchUnreadCount(accessToken) }
                val meTooCountsDeferred = async { fetchMeTooCounts(accessToken) }
                val myMeTooDeferred = async { fetchMyMeToos(accessToken) }
                val summariesDeferred = async { fetchThreadSummaries(accessToken) }
                val starterDeferred = async { buildDiscussionStarter(accessToken) }

                val articles = articlesDeferred.await()
                val favoriteIds = favoritesDeferred.await()
                val userLabels = userLabelsDeferred.await()
                val allTags = allTagsDeferred.await()
                val commentCounts = commentCountsDeferred.await()
                val forumPosts = forumDeferred.await()
                val forumFavoriteIds = forumFavDeferred.await()
                val unreadCount = unreadDeferred.await()
                val meTooCounts = meTooCountsDeferred.await()
                val myMeToos = myMeTooDeferred.await()
                val summaries = summariesDeferred.await()
                val starter = starterDeferred.await()

                // Map user labels -> tag IDs (case-insensitive match)
                val tagByName = allTags.associateBy { it.name.lowercase() }
                val matchedTagIds = mutableSetOf<String>()

                for (label in userLabels) {
                    tagByName[label.lowercase()]?.let { matchedTagIds.add(it.id) }
                }

                _state.value = _state.value.copy(
                    articles = articles,
                    userMatchingTagIds = matchedTagIds,
                    favoriteIds = favoriteIds,
                    commentCounts = commentCounts,
                    forumPosts = forumPosts,
                    forumFavoriteIds = forumFavoriteIds,
                    unreadCount = unreadCount,
                    meTooCountMap = meTooCounts,
                    myMeTooIds = myMeToos,
                    threadSummaries = summaries,
                    discussionStarter = starter,
                    loading = false
                )

                Log.d(TAG, "Loaded ${articles.size} articles, ${forumPosts.size} forum posts, ${matchedTagIds.size} matching tags, $unreadCount unread, ${meTooCounts.size} me-too counts")
            } catch (e: Exception) {
                Log.e(TAG, "loadAll error", e)
                _state.value = _state.value.copy(loading = false, error = e.message)
            }
        }
    }

    // =====================
    // ME TOO METHODS
    // =====================

    private suspend fun fetchMeTooCounts(accessToken: String): Map<String, Int> {
        return try {
            val response = client.get("$supabaseUrl/rest/v1/forum_me_too_counts") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                parameter("select", "post_id,me_too_count")
            }
            if (response.status.value !in 200..299) {
                Log.w(TAG, "fetchMeTooCounts: ${response.status}")
                emptyMap()
            } else {
                val rows: List<MeTooCountRow> = response.body()
                rows.associate { it.postId to it.meTooCount }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchMeTooCounts error: ${e.message}")
            emptyMap()
        }
    }

    private suspend fun fetchMyMeToos(accessToken: String): Set<String> {
        return try {
            val rows: List<MeTooRow> = fetchList(accessToken, "forum_me_too", "post_id")
            rows.map { it.postId }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "fetchMyMeToos error: ${e.message}")
            emptySet()
        }
    }

    /**
     * Unified heart action: toggles both me-too (public count) and
     * forum_favorites (follow for notifications) in one tap.
     */
    fun toggleMeToo(accessToken: String, postId: String) {
        viewModelScope.launch {
            val currentIds = _state.value.myMeTooIds
            val currentCounts = _state.value.meTooCountMap
            val currentCount = currentCounts[postId] ?: 0
            val currentFavIds = _state.value.forumFavoriteIds

            if (postId in currentIds) {
                // Optimistic remove from both
                _state.value = _state.value.copy(
                    myMeTooIds = currentIds - postId,
                    meTooCountMap = currentCounts + (postId to maxOf(0, currentCount - 1)),
                    forumFavoriteIds = currentFavIds - postId
                )
                try {
                    // Remove me-too
                    client.delete("$supabaseUrl/rest/v1/forum_me_too") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        header("apikey", supabaseKey)
                        parameter("post_id", "eq.$postId")
                    }
                    // Remove favorite (unfollows notifications)
                    client.delete("$supabaseUrl/rest/v1/forum_favorites") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        header("apikey", supabaseKey)
                        parameter("post_id", "eq.$postId")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "toggleMeToo remove error", e)
                    _state.value = _state.value.copy(
                        myMeTooIds = currentIds,
                        meTooCountMap = currentCounts,
                        forumFavoriteIds = currentFavIds
                    )
                }
            } else {
                // Optimistic add to both
                _state.value = _state.value.copy(
                    myMeTooIds = currentIds + postId,
                    meTooCountMap = currentCounts + (postId to currentCount + 1),
                    forumFavoriteIds = currentFavIds + postId
                )
                try {
                    // Add me-too
                    client.post("$supabaseUrl/rest/v1/forum_me_too") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        header("apikey", supabaseKey)
                        contentType(ContentType.Application.Json)
                        setBody(mapOf("post_id" to postId))
                    }
                    // Add favorite (follows for notifications)
                    client.post("$supabaseUrl/rest/v1/forum_favorites") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        header("apikey", supabaseKey)
                        contentType(ContentType.Application.Json)
                        setBody(mapOf("post_id" to postId))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "toggleMeToo add error", e)
                    _state.value = _state.value.copy(
                        myMeTooIds = currentIds,
                        meTooCountMap = currentCounts,
                        forumFavoriteIds = currentFavIds
                    )
                }
            }
        }
    }

    // =====================
    // THREAD SUMMARIES
    // =====================

    private suspend fun fetchThreadSummaries(accessToken: String): Map<String, String> {
        return try {
            val response = client.get("$supabaseUrl/rest/v1/forum_thread_summaries") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                parameter("select", "post_id,summary,comment_count,generated_at")
            }
            if (response.status.value !in 200..299) {
                Log.w(TAG, "fetchThreadSummaries: ${response.status}")
                emptyMap()
            } else {
                val rows: List<ThreadSummaryRow> = response.body()
                rows.associate { it.postId to it.summary }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchThreadSummaries error: ${e.message}")
            emptyMap()
        }
    }

    /** Trigger summary generation after posting a comment (fire-and-forget). Works for both forum posts and articles. */
    fun triggerSummaryGeneration(accessToken: String, postId: String) {
        viewModelScope.launch {
            try {
                client.post("$supabaseUrl/functions/v1/summarize-forum-thread") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    header("apikey", supabaseKey)
                    contentType(ContentType.Application.Json)
                    setBody(mapOf("post_id" to postId))
                }
            } catch (e: Exception) {
                Log.w(TAG, "triggerSummaryGeneration error: ${e.message}")
            }
        }
    }

    // =====================
    // DISCUSSION STARTER
    // =====================

    /**
     * Builds a contextual discussion starter from the user's top trigger.
     * Counts how many other users share that trigger and links to a relevant
     * pinned topic or article tag.
     */
    private suspend fun buildDiscussionStarter(accessToken: String): DiscussionStarter? {
        try {
            // Get user's top trigger (highest prediction_value)
            val triggers: List<UserTriggerSlim> = fetchList(accessToken, "user_triggers", "label,prediction_value")
            val severityOrder = mapOf("HIGH" to 3, "MILD" to 2, "LOW" to 1)
            val topTrigger = triggers
                .filter { it.predictionValue?.uppercase() in severityOrder }
                .maxByOrNull { severityOrder[it.predictionValue?.uppercase()] ?: 0 }
                ?: return null

            // Count how many users share this trigger (approximate — count rows in user_triggers with same label)
            val response = client.get("$supabaseUrl/rest/v1/user_triggers") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                header("Prefer", "count=exact")
                parameter("label", "eq.${topTrigger.label}")
                parameter("select", "id")
                parameter("limit", "0")
            }
            val range = response.headers["content-range"]
            val communityCount = range?.substringAfter("/")?.toIntOrNull() ?: 0

            // Map trigger to a relevant pinned topic
            val triggerLower = topTrigger.label.lowercase()
            val pinnedTopicId = when {
                triggerLower.contains("sleep") || triggerLower.contains("insomnia") || triggerLower.contains("fatigue") ->
                    "00000000-0000-0000-0000-000000000004"  // Sleep routines
                triggerLower.contains("stress") || triggerLower.contains("anxiety") || triggerLower.contains("work") ->
                    "00000000-0000-0000-0000-000000000003"  // Managing at work
                triggerLower.contains("food") || triggerLower.contains("diet") || triggerLower.contains("alcohol") ||
                triggerLower.contains("cheese") || triggerLower.contains("chocolate") || triggerLower.contains("caffeine") ->
                    "00000000-0000-0000-0000-000000000006"  // Food & diet
                triggerLower.contains("weather") || triggerLower.contains("barometric") || triggerLower.contains("humidity") ->
                    "00000000-0000-0000-0000-000000000002"  // Surprising triggers
                else -> "00000000-0000-0000-0000-000000000001"  // What works for you (catch-all)
            }

            // Also find matching article tag for deep-linking to articles view
            val allTags: List<TagRow> = fetchList(accessToken, "tags", "id,name,category")
            val matchingTag = allTags.find { it.name.equals(topTrigger.label, ignoreCase = true) }

            if (communityCount <= 1) return null  // Don't show if no one else has it

            return DiscussionStarter(
                triggerName = topTrigger.label,
                communityCount = communityCount,
                pinnedTopicId = pinnedTopicId,
                tagId = matchingTag?.id  // for filtering articles by this trigger tag
            )
        } catch (e: Exception) {
            Log.w(TAG, "buildDiscussionStarter error: ${e.message}")
            return null
        }
    }

    // =====================
    // FORUM METHODS
    // =====================

    private suspend fun fetchForumPosts(accessToken: String): List<ForumPostRow> {
        return try {
            val response = client.get("$supabaseUrl/rest/v1/forum_posts") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                parameter("status", "eq.active")
                parameter("select", "id,user_id,title,body,attachment,reply_count,created_at,updated_at,profiles(display_name,avatar_url)")
                parameter("order", "updated_at.desc.nullslast")
                parameter("limit", "100")
            }
            if (response.status.value !in 200..299) {
                Log.e(TAG, "fetchForumPosts: ${response.status} ${response.bodyAsText()}")
                emptyList()
            } else {
                val bodyText = response.bodyAsText()
                Log.d(TAG, "fetchForumPosts raw: ${bodyText.take(500)}")
                Json { ignoreUnknownKeys = true; explicitNulls = false }
                    .decodeFromString<List<ForumPostRow>>(bodyText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchForumPosts error", e)
            emptyList()
        }
    }

    fun refreshForumPosts(accessToken: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(forumLoading = true)
            val posts = fetchForumPosts(accessToken)
            _state.value = _state.value.copy(forumPosts = posts, forumLoading = false)
        }
    }

    fun createForumPost(accessToken: String, title: String, body: String, attachment: JsonObject? = null) {
        viewModelScope.launch {
            try {
                val payload = buildMap<String, Any> {
                    put("title", title)
                    put("body", body)
                }
                // Build JSON string manually to include optional attachment
                val jsonBody = buildString {
                    append("{")
                    append("\"title\":${Json.encodeToString(kotlinx.serialization.serializer<String>(), title)}")
                    append(",\"body\":${Json.encodeToString(kotlinx.serialization.serializer<String>(), body)}")
                    append(",\"status\":\"active\"")
                    if (attachment != null) {
                        append(",\"attachment\":${attachment}")
                    }
                    append("}")
                }

                val response = client.post("$supabaseUrl/rest/v1/forum_posts") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    header("apikey", supabaseKey)
                    header("Prefer", "return=minimal")
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody)
                }
                if (response.status.value in 200..299) {
                    Log.d(TAG, "Forum post created")
                    refreshForumPosts(accessToken)
                    // Refresh again after moderation has had time to process
                    launch {
                        val preCount = _state.value.forumPosts.size
                        kotlinx.coroutines.delay(4000L)
                        refreshForumPosts(accessToken)
                        if (_state.value.forumPosts.size < preCount) {
                            _state.value = _state.value.copy(showModerationAlert = true)
                        }
                    }
                } else {
                    Log.e(TAG, "createForumPost failed: ${response.status} ${response.bodyAsText()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "createForumPost error", e)
            }
        }
    }

    fun deleteForumPost(accessToken: String, postId: String) {
        viewModelScope.launch {
            // Optimistic remove
            _state.value = _state.value.copy(
                forumPosts = _state.value.forumPosts.filter { it.id != postId }
            )
            try {
                client.delete("$supabaseUrl/rest/v1/forum_posts") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    header("apikey", supabaseKey)
                    parameter("id", "eq.$postId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteForumPost error", e)
                refreshForumPosts(accessToken)
            }
        }
    }

    // Forum comments
    fun loadForumComments(accessToken: String, postId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(forumCommentsLoading = true)
            try {
                val response = client.get("$supabaseUrl/rest/v1/forum_comments") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    header("apikey", supabaseKey)
                    parameter("post_id", "eq.$postId")
                    parameter("status", "eq.active")
                    parameter("select", "id,post_id,user_id,parent_id,body,attachment,created_at,profiles(display_name,avatar_url)")
                    parameter("order", "created_at.asc")
                }
                if (response.status.value in 200..299) {
                    val comments: List<ForumCommentRow> = response.body()
                    _state.value = _state.value.copy(forumComments = comments, forumCommentsLoading = false)
                } else {
                    Log.e(TAG, "loadForumComments: ${response.status}")
                    _state.value = _state.value.copy(forumCommentsLoading = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadForumComments error", e)
                _state.value = _state.value.copy(forumCommentsLoading = false)
            }
        }
    }

    fun postForumComment(accessToken: String, postId: String, body: String, parentId: String? = null, attachment: JsonObject? = null) {
        viewModelScope.launch {
            try {
                val jsonBody = buildString {
                    append("{")
                    append("\"post_id\":\"$postId\"")
                    append(",\"body\":${Json.encodeToString(kotlinx.serialization.serializer<String>(), body)}")
                    append(",\"status\":\"active\"")
                    if (parentId != null) append(",\"parent_id\":\"$parentId\"")
                    if (attachment != null) append(",\"attachment\":${attachment}")
                    append("}")
                }

                Log.d(TAG, "postForumComment: postId=$postId, body=${jsonBody.take(200)}")

                val response = client.post("$supabaseUrl/rest/v1/forum_comments") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    header("apikey", supabaseKey)
                    header("Prefer", "return=minimal")
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody)
                }
                Log.d(TAG, "postForumComment response: ${response.status}")
                if (response.status.value in 200..299) {
                    loadForumComments(accessToken, postId)
                    // Fire-and-forget: trigger AI summary if thread qualifies
                    triggerSummaryGeneration(accessToken, postId)
                    // Refresh again after moderation has had time to process
                    launch {
                        val preCount = _state.value.forumComments.size
                        kotlinx.coroutines.delay(4000L)
                        loadForumComments(accessToken, postId)
                        if (_state.value.forumComments.size < preCount) {
                            _state.value = _state.value.copy(showModerationAlert = true)
                        }
                    }
                } else {
                    Log.e(TAG, "postForumComment failed: ${response.status} ${response.bodyAsText()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "postForumComment error", e)
            }
        }
    }

    fun deleteForumComment(accessToken: String, commentId: String, postId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                forumComments = _state.value.forumComments.filter { it.id != commentId }
            )
            try {
                client.delete("$supabaseUrl/rest/v1/forum_comments") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    header("apikey", supabaseKey)
                    parameter("id", "eq.$commentId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteForumComment error", e)
                loadForumComments(accessToken, postId)
            }
        }
    }

    // =====================
    // ARTICLE METHODS
    // =====================

    /**
     * Collects all label strings from the user's profile that should match article tags:
     * - Triggers with prediction_value LOW/MILD/HIGH or that are favorited
     * - Prodromes with prediction_value LOW/MILD/HIGH or favorited
     * - All user medicines (having them = relevant) + favorited ones
     * - All user reliefs + favorited ones
     */
    private suspend fun collectUserLabels(accessToken: String): Set<String> {
        val labels = mutableSetOf<String>()
        val validPredictions = setOf("LOW", "MILD", "HIGH")

        try {
            // -- Triggers: prediction_value at least LOW --
            val triggers: List<UserTriggerSlim> = fetchList(accessToken, "user_triggers", "label,prediction_value")
            for (t in triggers) {
                if (t.predictionValue?.uppercase() in validPredictions) {
                    labels.add(t.label)
                }
            }

            // -- Trigger favorites --
            val triggerPrefs: List<TriggerPrefSlim> = fetchList(accessToken, "trigger_preferences", "status,user_triggers(label)")
            for (p in triggerPrefs) {
                if (p.status == "frequent") p.trigger?.label?.let { labels.add(it) }
            }

            // -- Prodromes: prediction_value at least LOW --
            val prodromes: List<UserProdromeSlim> = fetchList(accessToken, "user_prodromes", "label,prediction_value")
            for (p in prodromes) {
                if (p.predictionValue?.uppercase() in validPredictions) {
                    labels.add(p.label)
                }
            }

            // -- Prodrome favorites --
            val prodromePrefs: List<ProdromePrefSlim> = fetchList(accessToken, "prodrome_preferences", "status,user_prodromes(label)")
            for (p in prodromePrefs) {
                if (p.status == "frequent") p.prodrome?.label?.let { labels.add(it) }
            }

            // -- Medicine favorites only (not the full pool) --
            val medPrefs: List<MedicinePrefSlim> = fetchList(accessToken, "medicine_preferences", "status,user_medicines(label)")
            for (p in medPrefs) {
                if (p.status == "frequent") p.medicine?.label?.let { labels.add(it) }
            }

            // -- Relief favorites only (not the full pool) --
            val reliefPrefs: List<ReliefPrefSlim> = fetchList(accessToken, "relief_preferences", "status,user_reliefs(label)")
            for (p in reliefPrefs) {
                if (p.status == "frequent") p.relief?.label?.let { labels.add(it) }
            }

        } catch (e: Exception) {
            Log.e(TAG, "collectUserLabels error", e)
        }

        Log.d(TAG, "Collected ${labels.size} user labels: ${labels.take(10)}...")
        return labels
    }

    private suspend inline fun <reified T> fetchList(accessToken: String, table: String, select: String): List<T> {
        return try {
            val response = client.get("$supabaseUrl/rest/v1/$table") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                parameter("select", select)
            }
            if (response.status.value !in 200..299) {
                Log.w(TAG, "fetchList $table: ${response.status}")
                emptyList()
            } else {
                response.body()
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchList $table error: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchArticles(accessToken: String): List<ArticleRow> {
        val response = client.get("$supabaseUrl/rest/v1/articles") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("status", "eq.published")
            parameter("select", "id,title,url,image_url,source,ai_summary,relevance_score,published_at,article_tags(confidence,tags(id,name,category))")
            parameter("order", "published_at.desc.nullslast")
            parameter("limit", "100")
        }
        if (response.status.value !in 200..299) {
            Log.e(TAG, "fetchArticles: ${response.status} ${response.bodyAsText()}")
            return emptyList()
        }
        return response.body<List<ArticleRow>>().map { article ->
            article.copy(
                title = decodeHtml(article.title),
                aiSummary = article.aiSummary?.let { decodeHtml(it) }
            )
        }
    }

    private suspend fun fetchArticleFavorites(accessToken: String): Set<String> {
        return try {
            val rows: List<ArticleFavoriteRow> = fetchList(accessToken, "article_favorites", "article_id")
            rows.map { it.articleId }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "fetchFavorites error", e)
            emptySet()
        }
    }

    private suspend fun fetchForumFavorites(accessToken: String): Set<String> {
        return try {
            val rows: List<ForumFavoriteRow> = fetchList(accessToken, "forum_favorites", "post_id")
            rows.map { it.postId }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "fetchForumFavorites error", e)
            emptySet()
        }
    }

    fun toggleForumFavorite(accessToken: String, postId: String) {
        viewModelScope.launch {
            val current = _state.value.forumFavoriteIds
            if (postId in current) {
                _state.value = _state.value.copy(forumFavoriteIds = current - postId)
                try {
                    client.delete("$supabaseUrl/rest/v1/forum_favorites") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        header("apikey", supabaseKey)
                        parameter("post_id", "eq.$postId")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "unfavorite forum error", e)
                    _state.value = _state.value.copy(forumFavoriteIds = current)
                }
            } else {
                _state.value = _state.value.copy(forumFavoriteIds = current + postId)
                try {
                    client.post("$supabaseUrl/rest/v1/forum_favorites") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        header("apikey", supabaseKey)
                        contentType(ContentType.Application.Json)
                        setBody(mapOf("post_id" to postId))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "favorite forum error", e)
                    _state.value = _state.value.copy(forumFavoriteIds = current)
                }
            }
        }
    }

    private suspend fun fetchCommentCounts(accessToken: String): Map<String, Int> {
        return try {
            val rows: List<CommentCountRow> = fetchList(accessToken, "article_comments", "article_id")
            rows.groupBy { it.articleId }.mapValues { it.value.size }
        } catch (e: Exception) {
            Log.e(TAG, "fetchCommentCounts error", e)
            emptyMap()
        }
    }

    private suspend fun fetchAllTags(accessToken: String): List<TagRow> {
        return try {
            val response = client.get("$supabaseUrl/rest/v1/tags") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                parameter("select", "id,name,category")
            }
            if (response.status.value !in 200..299) emptyList() else response.body()
        } catch (e: Exception) {
            Log.e(TAG, "fetchAllTags error", e)
            emptyList()
        }
    }

    // =====================
    // NOTIFICATIONS
    // =====================

    private suspend fun fetchUnreadCount(accessToken: String): Int {
        return try {
            val response = client.get("$supabaseUrl/rest/v1/community_notifications") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                header("Prefer", "count=exact")
                parameter("read", "eq.false")
                parameter("select", "id")
                parameter("limit", "0")
            }
            // Supabase returns count in content-range header: "0-0/5"
            val range = response.headers["content-range"]
            range?.substringAfter("/")?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "fetchUnreadCount error", e)
            0
        }
    }

    /** Call when user opens the Community screen to mark all as read */
    fun markAllRead(accessToken: String) {
        viewModelScope.launch {
            try {
                client.patch("$supabaseUrl/rest/v1/community_notifications") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    header("apikey", supabaseKey)
                    header("Prefer", "return=minimal")
                    parameter("read", "eq.false")
                    contentType(ContentType.Application.Json)
                    setBody(mapOf("read" to true))
                }
            } catch (_: Exception) {}
            _state.value = _state.value.copy(unreadCount = 0)
        }
    }

    /** Lightweight unread check — call from MainActivity without full loadAll */
    fun refreshUnreadCount(accessToken: String) {
        viewModelScope.launch {
            val count = fetchUnreadCount(accessToken)
            _state.value = _state.value.copy(unreadCount = count)
        }
    }

    /**
     * Unified article heart: toggles me-too (public count), article_favorites (Saved tab + notifications).
     */
    fun toggleFavorite(accessToken: String, articleId: String) {
        viewModelScope.launch {
            val currentFavs = _state.value.favoriteIds
            val currentIds = _state.value.myMeTooIds
            val currentCounts = _state.value.meTooCountMap
            val currentCount = currentCounts[articleId] ?: 0

            if (articleId in currentFavs) {
                // Optimistic remove from all
                _state.value = _state.value.copy(
                    favoriteIds = currentFavs - articleId,
                    myMeTooIds = currentIds - articleId,
                    meTooCountMap = currentCounts + (articleId to maxOf(0, currentCount - 1))
                )
                try {
                    client.delete("$supabaseUrl/rest/v1/article_favorites") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        header("apikey", supabaseKey)
                        parameter("article_id", "eq.$articleId")
                    }
                    client.delete("$supabaseUrl/rest/v1/forum_me_too") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        header("apikey", supabaseKey)
                        parameter("post_id", "eq.$articleId")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "unfavorite article error", e)
                    _state.value = _state.value.copy(
                        favoriteIds = currentFavs,
                        myMeTooIds = currentIds,
                        meTooCountMap = currentCounts
                    )
                }
            } else {
                // Optimistic add to all
                _state.value = _state.value.copy(
                    favoriteIds = currentFavs + articleId,
                    myMeTooIds = currentIds + articleId,
                    meTooCountMap = currentCounts + (articleId to currentCount + 1)
                )
                try {
                    client.post("$supabaseUrl/rest/v1/article_favorites") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        header("apikey", supabaseKey)
                        contentType(ContentType.Application.Json)
                        setBody(mapOf("article_id" to articleId))
                    }
                    client.post("$supabaseUrl/rest/v1/forum_me_too") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        header("apikey", supabaseKey)
                        contentType(ContentType.Application.Json)
                        setBody(mapOf("post_id" to articleId))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "favorite article error", e)
                    _state.value = _state.value.copy(
                        favoriteIds = currentFavs,
                        myMeTooIds = currentIds,
                        meTooCountMap = currentCounts
                    )
                }
            }
        }
    }

    /** Score articles by tag overlap with user's matching tags + boost for favorited articles */
    fun getForYouArticles(): List<ArticleRow> {
        val s = _state.value
        if (s.userMatchingTagIds.isEmpty()) return s.articles
        return s.articles
            .map { article ->
                val tagIds = article.articleTags
                    ?.mapNotNull { it.tags?.id }
                    ?.toSet() ?: emptySet()
                val overlap = tagIds.intersect(s.userMatchingTagIds).size
                val favBoost = if (article.id in s.favoriteIds) 2 else 0
                article to (overlap + favBoost)
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    fun getLatestArticles(): List<ArticleRow> {
        return _state.value.articles.sortedByDescending { it.publishedAt }
    }

    fun getAllTags(): List<TagRef> {
        return _state.value.articles
            .flatMap { it.articleTags?.mapNotNull { at -> at.tags } ?: emptyList() }
            .distinctBy { it.id }
            .sortedBy { it.name }
    }

    fun getArticlesByTag(tagId: String): List<ArticleRow> {
        return _state.value.articles.filter { article ->
            article.articleTags?.any { it.tags?.id == tagId } == true
        }
    }

    // =====================
    // REPORT CONTENT
    // =====================

    fun reportContent(accessToken: String, postId: String?, commentId: String?, reason: String, articleCommentId: String? = null) {
        viewModelScope.launch {
            try {
                // 1. Insert into forum_reports table
                val body = buildString {
                    append("{")
                    append("\"reason\":${Json.encodeToString(kotlinx.serialization.serializer<String>(), reason)}")
                    if (postId != null) append(",\"post_id\":\"$postId\"")
                    if (commentId != null) append(",\"comment_id\":\"$commentId\"")
                    if (articleCommentId != null) append(",\"article_comment_id\":\"$articleCommentId\"")
                    append("}")
                }
                client.post("$supabaseUrl/rest/v1/forum_reports") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    header("apikey", supabaseKey)
                    header("Prefer", "return=minimal")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

                // 2. Send email notification to help@migraineme.app
                val subject = when {
                    postId != null -> "Forum post reported"
                    articleCommentId != null -> "Article comment reported"
                    else -> "Forum comment reported"
                }
                val html = buildString {
                    append("<h2>New Community Report</h2>")
                    append("<p><strong>Reason:</strong> $reason</p>")
                    if (postId != null) append("<p><strong>Post ID:</strong> $postId</p>")
                    if (commentId != null) append("<p><strong>Forum Comment ID:</strong> $commentId</p>")
                    if (articleCommentId != null) append("<p><strong>Article Comment ID:</strong> $articleCommentId</p>")
                    append("<p>Please review this content in the admin dashboard.</p>")
                }
                val emailBody = "{\"to\":\"help@migraineme.app\",\"subject\":\"$subject\",\"html\":${Json.encodeToString(kotlinx.serialization.serializer<String>(), html)}}"
                client.post("$supabaseUrl/functions/v1/send-email") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    header("apikey", supabaseKey)
                    contentType(ContentType.Application.Json)
                    setBody(emailBody)
                }

                Log.d(TAG, "reportContent: submitted reason=$reason postId=$postId commentId=$commentId articleCommentId=$articleCommentId")
            } catch (e: Exception) {
                Log.e(TAG, "reportContent error", e)
            }
        }
    }
}
