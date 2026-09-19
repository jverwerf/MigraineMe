package com.migraineme

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * The one app-wide disk cache for exercise films, so a film watched once plays
 * offline afterwards. SimpleCache allows a single instance per directory per
 * process, hence the object: every player shares this one and nobody releases
 * it. Least-recently-used eviction at ~200 MB (six films are ~6 MB each today).
 */
@OptIn(UnstableApi::class)
object ExerciseVideoCache {
    private const val DIR_NAME = "exercise_films"
    private const val MAX_BYTES = 200L * 1024L * 1024L

    @Volatile
    private var cache: SimpleCache? = null

    private fun get(context: Context): SimpleCache {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: run {
                val appCtx = context.applicationContext
                SimpleCache(
                    File(appCtx.cacheDir, DIR_NAME),
                    LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                    StandaloneDatabaseProvider(appCtx)
                ).also { cache = it }
            }
        }
    }

    /**
     * Reads through the cache and fills it from the network as the film plays.
     * On a cache write error it carries on from the network rather than failing
     * playback.
     */
    fun dataSourceFactory(context: Context): DataSource.Factory = cacheFactory(context)

    private fun cacheFactory(context: Context): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(get(context))
            .setUpstreamDataSourceFactory(
                DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    // ── Prefetch: the attack routines, so they are there offline when needed ──

    /** Outlives the screen that asked: a download carries on after the user leaves the list. */
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Routine ids already attempted in this app session. One attempt each, no retry loop. */
    private val prefetchAttempted = mutableSetOf<String>()

    /**
     * Fill the cache with the two "during an attack" films, in the background.
     * Same cache and same cache keys (the URL) as the player, so a prefetched
     * film is exactly the one the player then reads offline.
     *
     * Returns at once; all work is on Dispatchers.IO. App language first, English
     * if that file fails. A film that is already fully cached is skipped without
     * touching the network. Every failure is swallowed: no UI, no retry.
     */
    fun prefetchAttackRoutines(context: Context, langCode: String) {
        val appCtx = context.applicationContext
        val todo = synchronized(prefetchAttempted) {
            ExerciseCatalogue.forWhen(ExerciseWhen.ATTACK).filter { prefetchAttempted.add(it.id) }
        }
        if (todo.isEmpty()) return
        prefetchScope.launch {
            for (routine in todo) {
                try {
                    val urls = listOf(
                        ExerciseCatalogue.filmUrl(routine, langCode),
                        ExerciseCatalogue.filmUrl(routine, ExerciseCatalogue.FALLBACK_LANG)
                    ).distinct()
                    for (url in urls) {
                        if (isFullyCached(appCtx, url) || fill(appCtx, url)) break
                    }
                } catch (_: Throwable) {
                    // silent by design
                }
            }
        }
    }

    /**
     * True when every byte of the film is on disk. The cache records the film's
     * total length (from the server's response) in its content metadata the
     * first time the URL is opened; "fully cached" means that length is known
     * AND the cache holds the whole span 0 until length with no hole. An unknown length —
     * never opened, or evicted since — counts as not cached.
     */
    fun isFullyCached(context: Context, url: String): Boolean {
        val cache = get(context)
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(url))
        if (length == C.LENGTH_UNSET.toLong() || length <= 0L) return false
        return cache.isCached(url, 0L, length)
    }

    /** One blocking full-file fill. False on any failure (missing language file, offline, disk). */
    private fun fill(context: Context, url: String): Boolean =
        try {
            CacheWriter(
                cacheFactory(context).createDataSource(),
                DataSpec(Uri.parse(url)),
                /* temporaryBuffer = */ null,
                /* progressListener = */ null
            ).cache()
            isFullyCached(context, url)
        } catch (_: Throwable) {
            false
        }
}
