// app/src/main/java/com/migraineme/BlogDetailScreen.kt
package com.migraineme

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun BlogDetailScreen(
    blogId: String,
    vm: CommunityViewModel,
    accessToken: String?,
    currentUserId: String?,
    insightsVm: InsightsViewModel,
    onBack: () -> Unit
) {
    val state by vm.state.collectAsState()
    val blog = state.blogs.find { it.id == blogId }

    if (blog == null) {
        Box(
            Modifier
                .fillMaxSize()
                .background(AppTheme.FadeColor),
            contentAlignment = Alignment.Center
        ) {
            Text("Blog not found", color = AppTheme.SubtleTextColor)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.FadeColor)
            .clipToBounds()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Tag (back handled by the app top bar) ──
        if (!blog.tag.isNullOrBlank()) {
            Text(
                blog.tag,
                color = AppTheme.AccentPurple,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
        }

        // ── Main card ──
        BaseCard {
            // Cover image
            if (!blog.coverImageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(blog.coverImageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(12.dp))
            }

            // Title
            Text(
                blog.title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    lineHeight = 26.sp
                )
            )

            // Byline
            Spacer(Modifier.height(6.dp))
            Text(
                buildByline(blog),
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelSmall
            )

            Spacer(Modifier.height(12.dp))

            // ── HTML body ──
            if (!blog.bodyHtml.isNullOrBlank()) {
                BlogHtmlBody(blog.bodyHtml)
            }
        }

        // ── FAQ ──
        if (!blog.faq.isNullOrEmpty()) {
            BaseCard {
                Text(
                    "FAQ",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                blog.faq.forEach { item ->
                    FaqRow(item)
                }
            }
        }

        // ── Comments card ──
        BaseCard {
            BlogCommentsSection(
                blogId = blogId,
                accessToken = accessToken,
                currentUserId = currentUserId,
                insightsVm = insightsVm,
                communityVm = vm
            )
        }

        Spacer(Modifier.height(80.dp))
    }
}

// ── FAQ expandable row ──

@Composable
private fun FaqRow(item: BlogFaq) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                item.q,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = AppTheme.AccentPurple,
                modifier = Modifier.size(20.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                item.a,
                color = AppTheme.BodyTextColor,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
            )
        }
    }
}

// ── Inline self-sizing HTML body renderer (framework WebView) ──

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BlogHtmlBody(bodyHtml: String) {
    val context = LocalContext.current
    var webViewHeight by remember(bodyHtml) { mutableStateOf(0) }

    val styledHtml = remember(bodyHtml) {
        """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          html, body { margin:0; padding:0; background:transparent; }
          body {
            color: rgba(255,255,255,0.82);
            font-family: -apple-system, Roboto, sans-serif;
            font-size: 16px;
            line-height: 1.65;
            word-wrap: break-word;
          }
          h1, h2, h3, h4, h5, h6 { color:#DCCEFF; font-weight:700; line-height:1.3; margin:18px 0 8px; }
          h1 { font-size:22px; } h2 { font-size:20px; } h3 { font-size:18px; }
          p { margin:0 0 14px; }
          a { color:#B97BFF; text-decoration:none; }
          strong, b { color:#FFFFFF; }
          ul, ol { padding-left:20px; margin:0 0 14px; }
          li { margin-bottom:6px; }
          img { max-width:100%; height:auto; border-radius:8px; }
          blockquote { border-left:3px solid #B97BFF; margin:0 0 14px; padding-left:12px; color:rgba(255,255,255,0.70); }
          code, pre { background:rgba(255,255,255,0.08); border-radius:4px; padding:2px 5px; font-size:14px; }
          hr { border:none; border-top:1px solid rgba(255,255,255,0.12); margin:18px 0; }
        </style>
        </head>
        <body>$bodyHtml</body>
        </html>
        """.trimIndent()
    }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (webViewHeight > 0) Modifier.height(webViewHeight.dp) else Modifier),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                @Suppress("DEPRECATION")
                settings.apply {
                    javaScriptEnabled = true
                    builtInZoomControls = false
                    displayZoomControls = false
                    setSupportZoom(false)
                    loadWithOverviewMode = false
                    useWideViewPort = false
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url ?: return false
                        return try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, url))
                            true
                        } catch (e: Exception) {
                            false
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript("(document.body.scrollHeight)") { value ->
                            value?.toFloatOrNull()?.let { h ->
                                if (h > 0f) webViewHeight = h.toInt()
                            }
                        }
                    }
                }
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(null, styledHtml, "text/html", "utf8", null)
        }
    )
}

// ── Helpers ──

private fun buildByline(blog: BlogRow): String {
    val parts = mutableListOf<String>()
    if (!blog.author.isNullOrBlank()) parts.add(blog.author)
    if (blog.readMinutes != null) parts.add("${blog.readMinutes} min read")
    formatBlogDate(blog.publishedAt)?.let { parts.add(it) }
    return parts.joinToString(" · ")
}

private fun formatBlogDate(isoDate: String?): String? {
    if (isoDate.isNullOrBlank()) return null
    return try {
        val instant = java.time.Instant.parse(isoDate)
        val formatter = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy")
            .withZone(java.time.ZoneId.systemDefault())
        formatter.format(instant)
    } catch (e: Exception) {
        null
    }
}
