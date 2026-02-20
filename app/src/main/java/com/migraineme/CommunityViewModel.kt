// app/src/main/java/com/migraineme/CommunityViewModel.kt
package com.migraineme

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
    // Notifications
    val unreadCount: Int = 0
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

                val articles = articlesDeferred.await()
                val favoriteIds = favoritesDeferred.await()
                val userLabels = userLabelsDeferred.await()
                val allTags = allTagsDeferred.await()
                val commentCounts = commentCountsDeferred.await()
                val forumPosts = forumDeferred.await()
                val forumFavoriteIds = forumFavDeferred.await()
                val unreadCount = unreadDeferred.await()

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
                    loading = false
                )

                Log.d(TAG, "Loaded ${articles.size} articles, ${forumPosts.size} forum posts, ${matchedTagIds.size} matching tags, $unreadCount unread")
            } catch (e: Exception) {
                Log.e(TAG, "loadAll error", e)
                _state.value = _state.value.copy(loading = false, error = e.message)
            }
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
        return response.body()
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

    fun toggleFavorite(accessToken: String, articleId: String) {
        viewModelScope.launch {
            val current = _state.value.favoriteIds
            if (articleId in current) {
                _state.value = _state.value.copy(favoriteIds = current - articleId)
                try {
                    client.delete("$supabaseUrl/rest/v1/article_favorites") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        header("apikey", supabaseKey)
                        parameter("article_id", "eq.$articleId")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "unfavorite error", e)
                    _state.value = _state.value.copy(favoriteIds = current)
                }
            } else {
                _state.value = _state.value.copy(favoriteIds = current + articleId)
                try {
                    client.post("$supabaseUrl/rest/v1/article_favorites") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        header("apikey", supabaseKey)
                        contentType(ContentType.Application.Json)
                        setBody(mapOf("article_id" to articleId))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "favorite error", e)
                    _state.value = _state.value.copy(favoriteIds = current)
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

    fun reportContent(accessToken: String, postId: String?, commentId: String?, reason: String) {
        viewModelScope.launch {
            try {
                // 1. Insert into forum_reports table
                val body = buildString {
                    append("{")
                    append("\"reason\":${Json.encodeToString(kotlinx.serialization.serializer<String>(), reason)}")
                    if (postId != null) append(",\"post_id\":\"$postId\"")
                    if (commentId != null) append(",\"comment_id\":\"$commentId\"")
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
                val subject = if (postId != null) "Forum post reported" else "Forum comment reported"
                val html = buildString {
                    append("<h2>New Forum Report</h2>")
                    append("<p><strong>Reason:</strong> $reason</p>")
                    if (postId != null) append("<p><strong>Post ID:</strong> $postId</p>")
                    if (commentId != null) append("<p><strong>Comment ID:</strong> $commentId</p>")
                    append("<p>Please review this content in the Supabase dashboard.</p>")
                }
                val emailBody = "{\"to\":\"help@migraineme.app\",\"subject\":\"$subject\",\"html\":${Json.encodeToString(kotlinx.serialization.serializer<String>(), html)}}"
                client.post("$supabaseUrl/functions/v1/send-email") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    header("apikey", supabaseKey)
                    contentType(ContentType.Application.Json)
                    setBody(emailBody)
                }

                Log.d(TAG, "reportContent: submitted reason=$reason postId=$postId commentId=$commentId")
            } catch (e: Exception) {
                Log.e(TAG, "reportContent error", e)
            }
        }
    }
}


