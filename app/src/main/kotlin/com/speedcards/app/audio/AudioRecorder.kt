package com.speedcards.app.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject

/** Records AAC audio (.m4a) into the cache folder. One recording at a time. */
class AudioRecorder @Inject constructor(@ApplicationContext private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    val isRecording get() = recorder != null

    fun start(): File {
        stop()
        val file = File.createTempFile("rec-", ".m4a", context.cacheDir)
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(44_100)
        r.setAudioEncodingBitRate(96_000)
        r.setAudioChannels(1)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        output = file
        return file
    }

    /** 0..1 input level, for a simple meter. */
    fun level(): Float = (recorder?.maxAmplitude ?: 0) / 32767f

    /** Stops and returns the file, or null if nothing usable was recorded. */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        val file = output
        output = null
        return try {
            r.stop()
            file
        } catch (_: RuntimeException) {
            // stop() throws when stopped immediately after start (no data).
            file?.delete()
            null
        } finally {
            r.release()
        }
    }
}

object AudioFiles {
    fun durationMs(file: File): Long = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(file.absolutePath)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally { release() }
        }
    }.getOrDefault(0L)

    /**
     * Cuts [source] to [startMs]..[endMs] without re-encoding (AAC frames are ~23 ms, so cuts are that precise).
     * Returns a new file in the same folder.
     */
    fun trim(source: File, startMs: Long, endMs: Long): File {
        val out = File(source.parentFile, source.nameWithoutExtension + "-trim.m4a")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(format)
            muxer.start()
            val startUs = startMs * 1000
            val endUs = endMs * 1000
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buffer = ByteBuffer.allocate(format.getIntegerOrDefault(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024))
            val info = android.media.MediaCodec.BufferInfo()
            var firstUs = -1L
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val time = extractor.sampleTime
                if (time > endUs) break
                if (time >= startUs - 30_000) {
                    if (firstUs < 0) firstUs = time
                    info.set(0, size, time - firstUs, extractor.sampleFlags)
                    muxer.writeSampleData(outTrack, buffer, info)
                }
                extractor.advance()
            }
            muxer.stop()
        } finally {
            extractor.release()
            runCatching { muxer?.release() }
        }
        return out
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int) =
        if (containsKey(key)) getInteger(key) else default
}
