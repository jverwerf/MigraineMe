// FILE: app/src/main/java/com/migraineme/ChatAssistantScreen.kt
package com.migraineme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ═══════════════════════════════════════════════════════════════
//  Data models
// ═══════════════════════════════════════════════════════════════

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String,     // "user" | "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isPremiumRequired: Boolean = false,
    val isRateLimited: Boolean = false,
    val remainingMessages: Int? = null
)

// ═══════════════════════════════════════════════════════════════
//  ViewModel
// ═══════════════════════════════════════════════════════════════

class ChatAssistantViewModel : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state

    private val client = HttpClient(Android) {
        engine {
            connectTimeout = 30_000
            socketTimeout = 60_000
        }
    }

    /** Max conversation turns sent to the edge function (6 turns = 3 user + 3 assistant). */
    private val MAX_HISTORY_TURNS = 6

    fun sendMessage(text: String, accessToken: String) {
        if (text.isBlank() || _state.value.isLoading) return

        val userMsg = ChatMessage(role = "user", content = text.trim())

        _state.update {
            it.copy(
                messages = it.messages + userMsg,
                isLoading = true,
                error = null,
                isPremiumRequired = false,
                isRateLimited = false
            )
        }

        viewModelScope.launch {
            try {
                val result = callChatAssistant(accessToken, text.trim())
                val assistantMsg = ChatMessage(role = "assistant", content = result.reply)
                _state.update {
                    it.copy(
                        messages = it.messages + assistantMsg,
                        isLoading = false,
                        remainingMessages = result.remaining
                    )
                }
            } catch (e: PremiumRequiredException) {
                // Remove the user message we just added — they can't use this
                _state.update {
                    it.copy(
                        messages = it.messages.dropLast(1),
                        isLoading = false,
                        isPremiumRequired = true
                    )
                }
            } catch (e: RateLimitedException) {
                _state.update {
                    it.copy(
                        messages = it.messages.dropLast(1),
                        isLoading = false,
                        isRateLimited = true
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Something went wrong"
                    )
                }
            }
        }
    }

    private data class ChatResult(val reply: String, val remaining: Int?)

    private suspend fun callChatAssistant(
        accessToken: String,
        message: String
    ): ChatResult {
        // Build conversation_history from previous messages (capped).
        // Exclude the message we just added (it goes as the top-level `message` field).
        val history = _state.value.messages
            .filter { it.role == "user" || it.role == "assistant" }
            .dropLast(1) // drop the user message we just appended
            .takeLast(MAX_HISTORY_TURNS)
            .map { msg ->
                JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                }
            }

        val requestBody = JSONObject().apply {
            put("message", message)
            put("conversation_history", JSONArray(history))
        }

        val url =
            "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/chat-assistant"

        val response = client.post(url) {
            header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(requestBody.toString())
        }

        val bodyText = response.bodyAsText()
        val statusCode = response.status.value

        // Handle specific error codes
        if (statusCode == 403) {
            val json = try { JSONObject(bodyText) } catch (_: Exception) { null }
            if (json?.optString("error") == "premium_required") {
                throw PremiumRequiredException()
            }
        }

        if (statusCode == 429) {
            throw RateLimitedException()
        }

        if (statusCode !in 200..299) {
            throw Exception("Chat failed (${statusCode})")
        }

        val json = JSONObject(bodyText)
        return ChatResult(
            reply = json.getString("reply"),
            remaining = if (json.has("remaining")) json.getInt("remaining") else null
        )
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearRateLimited() {
        _state.update { it.copy(isRateLimited = false) }
    }

    private class PremiumRequiredException : Exception("Premium required")
    private class RateLimitedException : Exception("Rate limited")
}

// ═══════════════════════════════════════════════════════════════
//  Screen composable
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatAssistantScreen(
    viewModel: ChatAssistantViewModel = remember { ChatAssistantViewModel() },
    onBack: () -> Unit,
    onNavigateToPaywall: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.state.collectAsState()
    var inputText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Auto-scroll to bottom when new messages arrive or loading starts
    LaunchedEffect(uiState.messages.size, uiState.isLoading) {
        val targetIndex = uiState.messages.size - 1 + (if (uiState.isLoading) 1 else 0)
        if (targetIndex >= 0) {
            listState.animateScrollToItem(targetIndex.coerceAtLeast(0))
        }
    }

    val bgGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF1A0028), Color(0xFF2A003D), Color(0xFF1A0028))
    )

    fun doSend() {
        if (inputText.isNotBlank() && !uiState.isLoading) {
            val token = SessionStore.readAccessToken(context)
            if (token != null) {
                viewModel.sendMessage(inputText, token)
                inputText = ""
                keyboardController?.hide()
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A0028).copy(alpha = 0.95f)
                )
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(bgGradient)
                .padding(padding)
        ) {
            Column(Modifier.fillMaxSize()) {

                // ── Messages list ────────────────────────────
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 8.dp)
                ) {
                    // Welcome card when no messages yet
                    if (uiState.messages.isEmpty() && !uiState.isLoading) {
                        item(key = "welcome") {
                            WelcomeCard(onSuggestionTap = { suggestion ->
                                inputText = suggestion
                                doSend()
                            })
                        }
                    }

                    items(uiState.messages, key = { it.id }) { message ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn() + slideInVertically(
                                initialOffsetY = { it / 2 }
                            )
                        ) {
                            ChatBubble(message)
                        }
                    }

                    // Typing indicator
                    if (uiState.isLoading) {
                        item(key = "typing") {
                            TypingIndicator()
                        }
                    }

                    // Error card
                    if (uiState.error != null) {
                        item(key = "error") {
                            ErrorCard(
                                message = uiState.error!!,
                                onDismiss = { viewModel.clearError() }
                            )
                        }
                    }

                    // Rate limit card
                    if (uiState.isRateLimited) {
                        item(key = "rate_limit") {
                            InfoCard(
                                message = "You\u2019ve reached your daily message limit. It resets at midnight — come back tomorrow!",
                                onDismiss = { viewModel.clearRateLimited() }
                            )
                        }
                    }

                    // Premium required card
                    if (uiState.isPremiumRequired) {
                        item(key = "premium") {
                            PremiumRequiredCard(onUpgrade = onNavigateToPaywall)
                        }
                    }
                }

                // ── Remaining messages indicator ─────────────
                uiState.remainingMessages?.let { remaining ->
                    if (remaining <= 5) {
                        Text(
                            text = "$remaining messages remaining today",
                            color = if (remaining <= 2) Color(0xFFFF8A8A)
                            else Color.White.copy(alpha = 0.4f),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        )
                    }
                }

                // ── Input bar ────────────────────────────────
                Surface(
                    color = Color(0xFF1A0028).copy(alpha = 0.95f),
                    tonalElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .navigationBarsPadding()
                            .imePadding(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                    "Ask about your health data\u2026",
                                    color = Color.White.copy(alpha = 0.4f),
                                    fontSize = 14.sp
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = AppTheme.AccentPurple,
                                focusedBorderColor = AppTheme.AccentPurple.copy(
                                    alpha = 0.5f
                                ),
                                unfocusedBorderColor = Color.White.copy(
                                    alpha = 0.15f
                                ),
                                focusedContainerColor = Color.White.copy(
                                    alpha = 0.06f
                                ),
                                unfocusedContainerColor = Color.White.copy(
                                    alpha = 0.04f
                                )
                            ),
                            shape = RoundedCornerShape(24.dp),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Send
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = { doSend() }
                            ),
                            singleLine = false,
                            maxLines = 4
                        )

                        Spacer(Modifier.width(8.dp))

                        // Send button
                        val canSend =
                            inputText.isNotBlank() && !uiState.isLoading
                        IconButton(
                            onClick = { doSend() },
                            enabled = canSend,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    if (canSend) AppTheme.AccentPurple
                                    else Color.White.copy(alpha = 0.1f)
                                )
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (canSend) Color.White
                                else Color.White.copy(alpha = 0.3f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Sub-composables
// ═══════════════════════════════════════════════════════════════

@Composable
private fun WelcomeCard(onSuggestionTap: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = AppTheme.BaseCardContainer,
        border = AppTheme.BaseCardBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "\u2726",
                fontSize = 28.sp,
                color = AppTheme.AccentPurple,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your Health Insights Assistant",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "I can help you understand your migraine patterns using your last 7 days of data \u2014 sleep, HRV, recovery, triggers, and more.",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 19.sp
            )
            Spacer(Modifier.height(16.dp))

            // Tappable suggestion chips
            val suggestions = listOf(
                "Why have I been feeling off lately?",
                "How\u2019s my sleep been this week?",
                "Any patterns before my last migraine?"
            )
            suggestions.forEach { suggestion ->
                SuggestionChip(
                    text = suggestion,
                    onClick = { onSuggestionTap(suggestion) },
                    modifier = Modifier.padding(vertical = 3.dp)
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Not medical advice. Based on your tracked data only.",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SuggestionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = AppTheme.AccentPurple.copy(alpha = 0.12f),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Text(
            text = "\u201C$text\u201D",
            color = AppTheme.AccentPurple.copy(alpha = 0.85f),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == "user"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser)
                AppTheme.AccentPurple.copy(alpha = 0.35f)
            else
                Color.White.copy(alpha = 0.08f),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                text = message.content,
                color = Color.White.copy(
                    alpha = if (isUser) 0.95f else 0.85f
                ),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(
                    horizontal = 14.dp,
                    vertical = 10.dp
                )
            )
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White.copy(alpha = 0.08f)
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val infiniteTransition =
                    rememberInfiniteTransition(label = "dots")
                repeat(3) { index ->
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.25f,
                        targetValue = 0.9f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600),
                            repeatMode = RepeatMode.Reverse,
                            initialStartOffset = StartOffset(index * 180)
                        ),
                        label = "dot$index"
                    )
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                AppTheme.AccentPurple.copy(alpha = alpha)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF3D1010).copy(alpha = 0.8f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                color = Color(0xFFFF8A8A),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = Color(0xFFFF8A8A), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun InfoCard(message: String, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1A1040).copy(alpha = 0.9f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                color = AppTheme.AccentPurple.copy(alpha = 0.85f),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text("OK", color = AppTheme.AccentPurple, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PremiumRequiredCard(onUpgrade: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AppTheme.BaseCardContainer,
        border = AppTheme.BaseCardBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Premium Feature",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "The chat assistant is available for premium subscribers. Upgrade to get personalised health insights from your data.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onUpgrade,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppTheme.AccentPurple
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Upgrade", color = Color.White)
            }
        }
    }
}
