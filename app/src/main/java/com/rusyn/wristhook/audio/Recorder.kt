package com.rusyn.wristhook.audio

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/**
 * Thin wrapper around MediaRecorder producing an AAC/MPEG-4 (.m4a) file in the app cache.
 * Mono, 44.1 kHz, 128 kbps — Whisper-friendly and small enough to upload over LTE
 * (~3 min ≈ 2.8 MB, well under Discord's file-size limit).
 */
class Recorder {

    private var recorder: MediaRecorder? = null
    private var file: File? = null

    val isRecording: Boolean
        get() = recorder != null

    /**
     * Peak amplitude since the previous call (0..32767), for the live level
     * meter and silence detection. Returns 0 when not recording. Note that
     * MediaRecorder.getMaxAmplitude() resets on each read, so there must be a
     * single consumer polling it.
     */
    fun amplitude(): Int = try {
        recorder?.maxAmplitude ?: 0
    } catch (_: IllegalStateException) {
        0
    }

    /**
     * Starts recording into a new cache file and returns it.
     * [onMaxDuration] fires (on an arbitrary thread) if [maxDurationMs] is hit.
     *
     * The cap is fixed at prepare() time, so the caller passes the largest
     * allowed value when the user's setting isn't loaded yet and enforces the
     * real limit itself (MainActivity's amplitude monitor does this).
     */
    fun start(context: Context, maxDurationMs: Int, onMaxDuration: () -> Unit): File {
        check(recorder == null) { "Already recording" }
        val out = File(context.cacheDir, "note-${System.currentTimeMillis()}.m4a")
        val r = MediaRecorder(context)
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            // 16 kHz mono is Whisper's native rate — faster encoder init, smaller
            // files, and no quality loss for speech transcription.
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(64_000)
            r.setMaxDuration(maxDurationMs)
            r.setOutputFile(out.absolutePath)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxDuration()
            }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            out.delete()
            throw e
        }
        recorder = r
        file = out
        return out
    }

    /**
     * Stops recording and returns the finished file, or null if nothing usable
     * was captured (e.g. stopped immediately after start).
     */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        val out = file
        file = null
        return try {
            r.stop()
            r.release()
            out
        } catch (e: RuntimeException) {
            // stop() throws if no valid audio was written; drop the empty file
            r.release()
            out?.delete()
            null
        }
    }

    /** Stops (if needed) and deletes the file. Used when the activity is destroyed mid-recording. */
    fun cancel() {
        val r = recorder ?: return
        recorder = null
        try {
            r.stop()
        } catch (_: RuntimeException) {
        }
        r.release()
        file?.delete()
        file = null
    }

    companion object {
        // Absolute ceiling regardless of the user's max-length setting; recording
        // starts before settings finish loading, so this is the prepare()-time cap.
        const val HARD_CAP_MS = 10 * 60 * 1000
    }
}
