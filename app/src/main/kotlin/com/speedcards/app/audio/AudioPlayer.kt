package com.speedcards.app.audio

import android.content.Context
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.speedcards.data.repo.MediaRepository
import com.speedcards.data.repo.PlayableAudio
import com.speedcards.data.repo.SpokenLine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays card audio: recorded files through ExoPlayer, or text-to-speech when a side has no recording.
 * Steps run in sequence so "reference audio, then your take" works for shadowing.
 * Must be used from the main thread.
 */
@Singleton
class AudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val media: MediaRepository,
) {
    sealed interface Step {
        data class Files(val files: List<File>, val clipStartMs: Long? = null, val clipEndMs: Long? = null) : Step
        data class Speak(val lines: List<SpokenLine>) : Step
    }

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    /** Last problem worth telling the user about (e.g. a missing TTS voice). */
    val problems = MutableStateFlow<String?>(null)

    private var player: ExoPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingWhileTtsLoads = mutableListOf<() -> Unit>()
    private var queue = ArrayDeque<Step>()
    private var speed = 1f
    private var generation = 0

    private fun exo(): ExoPlayer = player ?: ExoPlayer.Builder(context).build().also { p ->
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) next()
            }

            // A missing or unreadable file shouldn't leave the queue stuck.
            override fun onPlayerError(error: PlaybackException) {
                problems.value = "Couldn't play a recording."
                next()
            }
        })
        player = p
    }

    private fun withTts(action: () -> Unit) {
        if (ttsReady) return action()
        pendingWhileTtsLoads += action
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (!ttsReady) problems.value = "Text-to-speech isn't available on this device."
            val actions = pendingWhileTtsLoads.toList()
            pendingWhileTtsLoads.clear()
            if (ttsReady) actions.forEach { it() } else { _playing.value = false }
        }
    }

    /** Recorded sounds if the side has any, otherwise its text-to-speech lines (when [tts] is on). */
    fun stepsFor(audio: PlayableAudio, tts: Boolean): List<Step> = when {
        audio.sounds.isNotEmpty() -> listOf(Step.Files(audio.sounds.map(media::file).filter { it.exists() }))
        tts && audio.speech.isNotEmpty() -> listOf(Step.Speak(audio.speech))
        else -> emptyList()
    }

    fun play(steps: List<Step>, speed: Float) {
        stop()
        this.speed = speed
        queue = ArrayDeque(steps.filter { it !is Step.Files || it.files.isNotEmpty() })
        if (queue.isEmpty()) return
        _playing.value = true
        next()
    }

    fun playFile(file: File, speed: Float = 1f, clipStartMs: Long? = null, clipEndMs: Long? = null) =
        play(listOf(Step.Files(listOf(file), clipStartMs, clipEndMs)), speed)

    fun stop() {
        generation++
        queue.clear()
        player?.stop()
        tts?.stop()
        _playing.value = false
    }

    fun release() {
        stop()
        player?.release(); player = null
        tts?.shutdown(); tts = null; ttsReady = false
    }

    private fun next() {
        val step = queue.removeFirstOrNull() ?: run { _playing.value = false; return }
        when (step) {
            is Step.Files -> {
                val p = exo()
                p.setMediaItems(step.files.map { f ->
                    MediaItem.Builder().setUri(Uri.fromFile(f)).apply {
                        if (step.clipStartMs != null || step.clipEndMs != null) {
                            setClippingConfiguration(
                                MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(step.clipStartMs ?: 0)
                                    .apply { step.clipEndMs?.let { setEndPositionMs(it) } }
                                    .build()
                            )
                        }
                    }.build()
                })
                p.playbackParameters = PlaybackParameters(speed)
                p.prepare()
                p.play()
            }
            is Step.Speak -> {
                val gen = generation
                withTts { if (gen == generation) speakLines(step.lines, gen) }
            }
        }
    }

    private fun speakLines(lines: List<SpokenLine>, gen: Int) {
        val engine = tts ?: return
        val remaining = ArrayDeque(lines)
        fun speakNext() {
            if (gen != generation) return
            val line = remaining.removeFirstOrNull() ?: return next()
            val locale = Locale.forLanguageTag(line.languageTag)
            if (engine.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) {
                problems.value = "No text-to-speech voice for ${locale.displayName}. Install one in Android Settings › Text-to-speech."
                return speakNext()
            }
            engine.language = locale
            engine.setSpeechRate(speed)
            engine.speak(line.text, TextToSpeech.QUEUE_FLUSH, null, "line-$gen-${remaining.size}")
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            // Callbacks arrive on a binder thread; hop back to main for ExoPlayer.
            override fun onDone(utteranceId: String?) { mainHandler.post { speakNext() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { mainHandler.post { speakNext() } }
        })
        speakNext()
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
}
