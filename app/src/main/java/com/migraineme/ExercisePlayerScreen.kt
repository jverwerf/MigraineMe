package com.migraineme

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * One routine: the film on top (16:9, full width, standard controls, never
 * autoplays), then what you need and the three science sections. Nothing else.
 *
 * The film is requested in the app language and falls back to English if that
 * file fails to load (only English films exist today). Playback reads through
 * ExerciseVideoCache, so a film watched once plays offline afterwards.
 */
@OptIn(UnstableApi::class)
@Composable
fun ExercisePlayerScreen(
    routineId: String?,
    onBack: () -> Unit
) {
    val routine = ExerciseCatalogue.byId(routineId)
    if (routine == null) {
        // Unknown id (stale deep link): nothing to show, step back.
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }

    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // The film's language is fixed when the screen opens; it is a file, not text.
    val langCode = remember(routine.id) { LangPrefs.get().code }

    // Survives rotation (the activity is recreated) so the film carries on where it was.
    var resumePositionMs by rememberSaveable(routine.id) { mutableStateOf(0L) }

    // Shown under the film when it could not be loaded at all because there is
    // no connection (and it is not in the cache). Static text; cleared as soon
    // as playback starts. Play retries: PlayerView re-prepares an idle player.
    var showNoConnection by remember(routine.id) { mutableStateOf(false) }

    val player = remember(routine.id) {
        var usingFallback = langCode == ExerciseCatalogue.FALLBACK_LANG
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(ExerciseVideoCache.dataSourceFactory(context))
            )
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        // Language film missing or unreadable: try the English one, once.
                        if (!usingFallback) {
                            usingFallback = true
                            val position = currentPosition
                            setMediaItem(
                                MediaItem.fromUri(
                                    ExerciseCatalogue.filmUrl(routine, ExerciseCatalogue.FALLBACK_LANG)
                                ),
                                position
                            )
                            prepare()
                        } else {
                            // Nothing left to try. Say so only when the cause is the
                            // connection, not a missing or unreadable file.
                            showNoConnection = error.errorCode in NO_CONNECTION_ERROR_CODES
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // Keep the screen awake while the film is actually playing.
                        view.keepScreenOn = isPlaying
                        if (isPlaying) showNoConnection = false
                    }
                })
                setMediaItem(MediaItem.fromUri(ExerciseCatalogue.filmUrl(routine, langCode)), resumePositionMs)
                playWhenReady = false // never autoplay
                prepare()
            }
    }

    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> resumePositionMs = player.currentPosition
                Lifecycle.Event.ON_STOP -> player.pause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view.keepScreenOn = false
            player.release()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    this.player = player
                }
            },
            update = { it.player = player },
            onRelease = { it.player = null },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showNoConnection) {
                LabelPlate {
                    Text(
                        t("No connection. This routine plays offline once it has been downloaded."),
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            BaseCard {
                Text(
                    t("You need: %s", t(routine.props)),
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            for (section in routine.sections) {
                BaseCard {
                    Text(
                        t(section.heading),
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    for (paragraph in section.paragraphs) {
                        Text(
                            t(paragraph),
                            color = AppTheme.BodyTextColor,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

/** Player errors that mean "no network", as opposed to a missing or broken file. */
private val NO_CONNECTION_ERROR_CODES = setOf(
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
)
