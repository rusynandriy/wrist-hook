package com.rusyn.wristhook.audio

import android.content.Context
import android.media.MediaRecorder
import com.rusyn.wristhook.data.Outbox
import java.io.File

/**
 * Thin wrapper around MediaRecorder producing an AAC/MPEG-4 (.m4a) file in app storage
 * (files/recording/, not the cache: the system may trim the cache before a failed send is retried).
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
     * Starts recording into a new file and returns it.
     * [onMaxDuration] fires (on an arbitrary thread) if [maxDurationMs] is hit.
     *
     * The cap is fixed at prepare() time, so the caller passes the largest
     * allowed value when the user's setting isn't loaded yet and enforces the
     * real limit itself (MainActivity's amplitude monitor does this).
     */
    fun start(context: Context, maxDurationMs: Int, onMaxDuration: () -> Unit): File {
        check(recorder == null) { "Already recording" }
        val out = File(Outbox.recordingDir(context), "note-${System.currentTimeMillis()}.m4a")
        val r = MediaRecorder(context)
        try {
            // VOICE_RECOGNITION requests the raw primary mic tuned for speech, with
            // no AGC/noise-suppression surprises. Plain MIC on Wear OS can route to a
            // processed or Bluetooth-SCO path, which was giving intermittently garbled
            // takes and, because the HAL then thrashes to feed buffers, a frozen UI.
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            // 48 kHz is the watch mic's native capture rate: recording here avoids the
            // in-HAL resampler that underran at 16 kHz (the source of the garbling).
            // Whisper downsamples to 16 kHz itself, so there's no downside. 64 kbps
            // keeps a full 10-min note ≈ 4.8 MB, safely under Discord's upload limit.
            r.setAudioSamplingRate(48_000)
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
        // Kept above the largest user option (10 min) so the configured limit — not
        // this safety net — is what normally stops a take.
        const val HARD_CAP_MS = 15 * 60 * 1000
    }
}
