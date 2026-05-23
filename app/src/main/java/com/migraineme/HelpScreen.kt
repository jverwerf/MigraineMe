// FILE: app/src/main/java/com/migraineme/HelpScreen.kt
//
// In-app Help Centre for Android. Reads help_categories + help_articles
// from Supabase via REST, caches the catalogue in SharedPreferences for
// offline use, and renders each article's markdown body with a small
// in-house parser (no extra dependencies).

package com.migraineme

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────────────
// Models
// ─────────────────────────────────────────────────────────────────────

data class HelpCategory(
    val slug: String,
    val name: String,
    val orderIndex: Int
)

data class HelpArticle(
    val slug: String,
    val categorySlug: String,
    val title: String,
    val bodyMarkdown: String,
    val searchText: String,
    val orderIndex: Int
)

// ─────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────

class HelpViewModel : ViewModel() {

    data class UiState(
        val categories: List<HelpCategory> = emptyList(),
        val articles: List<HelpArticle> = emptyList(),
        val query: String = "",
        val isLoading: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val prefsName = "help_centre_cache_v1"
    private val keyCategories = "categories"
    private val keyArticles = "articles"

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun loadCache(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val cats = prefs.getString(keyCategories, null)?.let { parseCategoriesJson(it) } ?: emptyList()
        val arts = prefs.getString(keyArticles, null)?.let { parseArticlesJson(it) } ?: emptyList()
        _state.update { it.copy(categories = cats, articles = arts) }
    }

    fun load(context: Context) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = _state.value.articles.isEmpty()) }
            try {
                val accessToken = SessionStore.getValidAccessToken(context.applicationContext) ?: return@launch
                val (cats, arts) = withContext(Dispatchers.IO) { fetchAll(accessToken) }
                val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(keyCategories, encodeCategoriesJson(cats))
                    .putString(keyArticles, encodeArticlesJson(arts))
                    .apply()
                _state.update { it.copy(categories = cats, articles = arts) }
            } catch (e: Exception) {
                android.util.Log.w("Help", "load failed", e)
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun articlesIn(cat: HelpCategory): List<HelpArticle> {
        val q = _state.value.query.trim().lowercase()
        val inCat = _state.value.articles
            .filter { it.categorySlug == cat.slug }
            .sortedBy { it.orderIndex }
        return if (q.isEmpty()) inCat else inCat.filter { it.searchText.contains(q) }
    }

    fun visibleCategories(): List<HelpCategory> =
        _state.value.categories
            .sortedBy { it.orderIndex }
            .filter { articlesIn(it).isNotEmpty() }

    // ─── Network ────────────────────────────────────────────────────

    private fun fetchAll(accessToken: String): Pair<List<HelpCategory>, List<HelpArticle>> {
        val base = BuildConfig.SUPABASE_URL
        val anon = BuildConfig.SUPABASE_ANON_KEY

        fun get(path: String): String {
            val req = Request.Builder()
                .url("$base$path")
                .get()
                .header("Authorization", "Bearer $accessToken")
                .header("apikey", anon)
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                return resp.body?.string() ?: "[]"
            }
        }

        val catsJson = get("/rest/v1/help_categories?select=*&order=order_index.asc")
        val artsJson = get("/rest/v1/help_articles?select=*&order=order_index.asc")
        return parseCategoriesJson(catsJson) to parseArticlesJson(artsJson)
    }

    private fun parseCategoriesJson(s: String): List<HelpCategory> {
        val arr = JSONArray(s)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            HelpCategory(
                slug = o.getString("slug"),
                name = o.getString("name"),
                orderIndex = o.optInt("order_index", 0)
            )
        }
    }

    private fun parseArticlesJson(s: String): List<HelpArticle> {
        val arr = JSONArray(s)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            HelpArticle(
                slug = o.getString("slug"),
                categorySlug = o.getString("category_slug"),
                title = o.getString("title"),
                bodyMarkdown = o.getString("body_markdown"),
                searchText = o.getString("search_text"),
                orderIndex = o.optInt("order_index", 0)
            )
        }
    }

    private fun encodeCategoriesJson(list: List<HelpCategory>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("slug", it.slug); put("name", it.name); put("order_index", it.orderIndex)
            })
        }
        return arr.toString()
    }

    private fun encodeArticlesJson(list: List<HelpArticle>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("slug", it.slug); put("category_slug", it.categorySlug)
                put("title", it.title); put("body_markdown", it.bodyMarkdown)
                put("search_text", it.searchText); put("order_index", it.orderIndex)
            })
        }
        return arr.toString()
    }
}

// ─────────────────────────────────────────────────────────────────────
// Help list screen
// ─────────────────────────────────────────────────────────────────────

@Composable
fun HelpScreen(
    onBack: () -> Unit,
    vm: HelpViewModel = remember { HelpViewModel() },
    onOpenArticle: (HelpArticle) -> Unit
) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) {
        vm.loadCache(ctx)
        vm.load(ctx)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header with back button so users have a clear way to leave the screen.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Spacer(Modifier.weight(1f))
            Text("Help", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
        }

        // Search
        OutlinedTextField(
            value = state.query,
            onValueChange = { vm.setQuery(it) },
            placeholder = { Text("Search help…", color = Color.White.copy(alpha = 0.5f)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Color.White.copy(alpha = 0.6f)) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) {
                        Icon(Icons.Outlined.Clear, "Clear", tint = Color.White.copy(alpha = 0.6f))
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions.Default,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color.White.copy(alpha = 0.3f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                focusedContainerColor = Color.White.copy(alpha = 0.04f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.04f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
        )

        when {
            state.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppTheme.AccentPurple)
                }
            }
            state.articles.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Outlined.HelpOutline, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No help articles yet", color = Color.White.copy(alpha = 0.6f))
                }
            }
            else -> {
                val cats = vm.visibleCategories()
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 40.dp, top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(cats, key = { it.slug }) { cat ->
                        val arts = vm.articlesIn(cat)
                        Column {
                            Text(
                                text = cat.name.uppercase(),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.55f),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                            Column(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.White.copy(alpha = 0.04f))
                            ) {
                                arts.forEach { article ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onOpenArticle(article) }
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(article.title, color = Color.White, modifier = Modifier.weight(1f))
                                        Text("›", color = Color.White.copy(alpha = 0.5f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Help detail screen
// ─────────────────────────────────────────────────────────────────────

/**
 * Looks up the article by slug from the cached help catalogue and renders
 * the detail. Used by the nav graph so we don't have to make HelpArticle
 * Parcelable just to pass it across a route.
 */
@Composable
fun HelpArticleDetailRoute(slug: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val vm: HelpViewModel = remember { HelpViewModel() }
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) {
        vm.loadCache(ctx)
        if (state.articles.none { it.slug == slug }) vm.load(ctx)
    }

    val article = state.articles.firstOrNull { it.slug == slug }
    if (article == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.isLoading) CircularProgressIndicator(color = AppTheme.AccentPurple)
            else Text("Article not found", color = Color.White.copy(alpha = 0.6f))
        }
    } else {
        HelpArticleDetailScreen(article = article, onBack = onBack)
    }
}

@Composable
fun HelpArticleDetailScreen(article: HelpArticle, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Header with back button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Spacer(Modifier.weight(1f))
            Text("Help", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    article.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            items(parseMarkdown(article.bodyMarkdown)) { block ->
                MarkdownBlockView(block)
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Markdown renderer (block-level: # / ## / ### / - / 1. / > / paragraph)
// ─────────────────────────────────────────────────────────────────────

sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    data class BulletList(val items: List<String>) : MarkdownBlock()
    data class NumberedList(val items: List<String>) : MarkdownBlock()
    data class BlockQuote(val text: String) : MarkdownBlock()
}

fun parseMarkdown(source: String): List<MarkdownBlock> {
    val out = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val bullets = mutableListOf<String>()
    val numbered = mutableListOf<String>()

    fun flushPara() {
        if (paragraph.isNotEmpty()) {
            out += MarkdownBlock.Paragraph(paragraph.joinToString(" "))
            paragraph.clear()
        }
    }
    fun flushBullets() {
        if (bullets.isNotEmpty()) {
            out += MarkdownBlock.BulletList(bullets.toList())
            bullets.clear()
        }
    }
    fun flushNumbered() {
        if (numbered.isNotEmpty()) {
            out += MarkdownBlock.NumberedList(numbered.toList())
            numbered.clear()
        }
    }
    fun flushAll() { flushPara(); flushBullets(); flushNumbered() }

    val numberedRe = Regex("""^\d+\.\s+""")

    for (rawLine in source.lines()) {
        val line = rawLine.trim()
        when {
            line.isEmpty() -> flushAll()
            line.startsWith("### ") -> { flushAll(); out += MarkdownBlock.Heading(3, line.removePrefix("### ")) }
            line.startsWith("## ")  -> { flushAll(); out += MarkdownBlock.Heading(2, line.removePrefix("## ")) }
            line.startsWith("# ")   -> { flushAll(); out += MarkdownBlock.Heading(1, line.removePrefix("# ")) }
            line.startsWith("> ")   -> { flushAll(); out += MarkdownBlock.BlockQuote(line.removePrefix("> ")) }
            line.startsWith("- ")   -> { flushPara(); flushNumbered(); bullets += line.removePrefix("- ") }
            numberedRe.containsMatchIn(line) -> {
                flushPara(); flushBullets()
                numbered += numberedRe.replaceFirst(line, "")
            }
            else -> { flushBullets(); flushNumbered(); paragraph += line }
        }
    }
    flushAll()
    return out
}

@Composable
fun MarkdownBlockView(block: MarkdownBlock) {
    when (block) {
        is MarkdownBlock.Heading -> {
            val fs = when (block.level) { 1 -> 22.sp; 2 -> 19.sp; else -> 16.sp }
            Text(
                annotateInline(block.text),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = fs,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        is MarkdownBlock.Paragraph -> {
            Text(annotateInline(block.text), color = Color.White.copy(alpha = 0.92f), fontSize = 15.sp)
        }
        is MarkdownBlock.BulletList -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                block.items.forEach { item ->
                    Row {
                        Text("•  ", color = AppTheme.AccentPurple, fontSize = 15.sp)
                        Text(annotateInline(item), color = Color.White.copy(alpha = 0.92f), fontSize = 15.sp)
                    }
                }
            }
        }
        is MarkdownBlock.NumberedList -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                block.items.forEachIndexed { i, item ->
                    Row {
                        Text("${i + 1}.  ", color = AppTheme.AccentPurple, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(annotateInline(item), color = Color.White.copy(alpha = 0.92f), fontSize = 15.sp)
                    }
                }
            }
        }
        is MarkdownBlock.BlockQuote -> {
            Row {
                Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(AppTheme.AccentPurple.copy(alpha = 0.5f)))
                Spacer(Modifier.width(12.dp))
                Text(annotateInline(block.text), color = Color.White.copy(alpha = 0.7f), fontStyle = FontStyle.Italic, fontSize = 15.sp)
            }
        }
    }
}

/** Render inline **bold** and *italic* into an AnnotatedString. Plain markdown only. */
fun annotateInline(text: String): AnnotatedString = buildAnnotatedString {
    val boldRe = Regex("""\*\*(.+?)\*\*""")
    val italicRe = Regex("""(?<![*])\*(?!\*)(.+?)(?<!\*)\*(?!\*)""")

    var cursor = 0
    while (cursor < text.length) {
        // Look for the closest bold or italic match from cursor.
        val bold = boldRe.find(text, cursor)
        val italic = italicRe.find(text, cursor)
        val next = listOfNotNull(bold, italic).minByOrNull { it.range.first }
        if (next == null) {
            append(text.substring(cursor))
            break
        }
        if (next.range.first > cursor) append(text.substring(cursor, next.range.first))
        val isBold = next === bold
        withStyle(SpanStyle(fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal, fontStyle = if (isBold) FontStyle.Normal else FontStyle.Italic)) {
            append(next.groupValues[1])
        }
        cursor = next.range.last + 1
    }
}
