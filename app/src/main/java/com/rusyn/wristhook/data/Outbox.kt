package com.rusyn.wristhook.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rusyn.wristhook.net.Uploader
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Durable queue of finished notes that haven't been delivered yet.
 *
 * A note moves into `files/outbox/` the moment the user commits to sending it
 * and leaves only after the webhook returns 2xx. `files/` (unlike the cache dir
 * notes used to live in) is never trimmed by the system, and [OutboxWorker]
 * keeps retrying with backoff until everything is sent, even if the app is
 * closed or the watch reboots in between.
 */
object Outbox {

    private const val WORK_NAME = "wristhook-outbox"
    private const val ORPHAN_AGE_MS = 20 * 60 * 1000L

    private val inFlight = mutableSetOf<String>()

    /**
     * Marks a note as being sent so the activity and the worker never upload the
     * same one twice. Per-file, not global: a worker stuck waiting for a radio
     * must not hold up the note the user just recorded.
     */
    fun claim(file: File): Boolean = synchronized(inFlight) { inFlight.add(file.name) }

    fun release(file: File) = synchronized(inFlight) { inFlight.remove(file.name) }

    private fun dir(context: Context) = File(context.filesDir, "outbox").apply { mkdirs() }

    /** Where in-progress recordings are written (moved into the outbox on send). */
    fun recordingDir(context: Context) = File(context.filesDir, "recording").apply { mkdirs() }

    /** Moves [file] into the outbox and returns its new location. */
    fun adopt(context: Context, file: File): File {
        val dest = File(dir(context), file.name)
        if (file.absolutePath == dest.absolutePath) return dest
        if (!file.renameTo(dest)) {
            file.copyTo(dest, overwrite = true)
            file.delete()
        }
        return dest
    }

    fun pending(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.length() > 0 }?.sortedBy { it.name }.orEmpty()

    /**
     * Pulls stranded notes into the outbox: takes from builds that kept failed
     * sends in the cache dir with no retry, and recordings whose process died
     * before they were sent. Anything touched in the last 20 min is left alone,
     * as it may still be recording or waiting in the tap-to-cancel window.
     */
    fun recoverOrphans(context: Context): Int {
        val cutoff = System.currentTimeMillis() - ORPHAN_AGE_MS
        val legacy = context.cacheDir.listFiles()?.toList().orEmpty()
            .filter { it.name.startsWith("note-") && it.name.endsWith(".m4a") }
        val stranded = recordingDir(context).listFiles()?.toList().orEmpty()
        val orphans = (legacy + stranded).filter { it.isFile && it.lastModified() < cutoff }
        orphans.forEach { f -> if (f.length() > 0) adopt(context, f) else f.delete() }
        return orphans.size
    }

    /**
     * Queues a background drain. [replace] restarts any pending backoff so a
     * fresh failure is retried promptly instead of waiting out a long delay.
     */
    fun schedule(context: Context, replace: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        // Retry loop with no network constraint on purpose: with the phone away
        // there is no default network until the worker asks for Wi-Fi/LTE itself.
        wm.enqueueUniqueWork(
            WORK_NAME,
            policy,
            OneTimeWorkRequestBuilder<OutboxWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
        // Plus a run the moment any network shows up (phone back in range, saved
        // Wi-Fi), instead of waiting out the retry loop's backoff.
        wm.enqueueUniqueWork(
            "$WORK_NAME-online",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }
}

/** Sends every queued note, oldest first; retries with backoff until the outbox is empty. */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Outbox.recoverOrphans(ctx)
        val settings = SettingsRepository(ctx).load()
        if (settings.webhookUrl.isBlank()) return Result.retry()

        var failed = 0
        for (file in Outbox.pending(ctx)) {
            if (!Outbox.claim(file)) continue // the activity is sending this one right now
            try {
                if (!file.exists()) continue
                val result = Uploader.upload(ctx, settings.webhookUrl, settings.authHeader, file)
                if (result.isSuccess) {
                    file.delete()
                    continue
                }
                failed++
                // No network at all: the rest would just wait out the same timeout.
                if (result.exceptionOrNull() !is Uploader.HttpStatusException) break
            } finally {
                Outbox.release(file)
            }
        }
        return if (failed > 0) Result.retry() else Result.success()
    }
}
