package com.rusyn.wristhook.data

import android.content.Context
import java.io.File

/**
 * Holding area for takes the user threw away (long-press cancel, Discard), so a
 * mis-cancel can still be undone and sent.
 *
 * Takes live in `files/cancelled/` (on disk, so they survive the app being killed)
 * for [MAX_AGE_MS], at most [MAX_KEPT] of them. The time a take was held is
 * encoded in its file name (`held-<millis>-<original name>`), so nothing else
 * needs persisting and the original name comes back on [release].
 *
 * Plain java.io only (no Android APIs) so it is unit-testable on the JVM.
 */
class HeldTakes(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {

    data class Take(val file: File, val heldAt: Long, val originalName: String)

    /**
     * Moves [file] into the holding area and returns it, or null if there was
     * nothing worth keeping (missing or empty — an empty file is deleted).
     */
    fun hold(file: File): Take? {
        if (!file.isFile) return null
        if (file.length() == 0L) {
            file.delete()
            return null
        }
        dir.mkdirs()
        val heldAt = now()
        val dest = File(dir, "$PREFIX$heldAt-${file.name}")
        move(file, dest)
        prune()
        return Take(dest, heldAt, file.name)
    }

    /** The take Undo acts on: the most recently held one that hasn't aged out. */
    fun newest(): Take? = takes().firstOrNull { !isExpired(it) }

    /** Drops takes older than [MAX_AGE_MS] and all but the [MAX_KEPT] newest. Returns how many went. */
    fun prune(): Int {
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        val takes = takes()
        val keep = takes.filterNot(::isExpired).take(MAX_KEPT).map { it.file }.toSet()
        // Anything else in here (expired, over the cap, empty or not ours) goes.
        val drop = files.filterNot { it in keep }
        drop.forEach { it.delete() }
        return drop.size
    }

    /**
     * Takes [take] out of the holding area into [destDir] under its original
     * name, ready to be sent. Returns null if it is gone or has aged out.
     */
    fun release(take: Take, destDir: File): File? {
        if (!take.file.isFile || isExpired(take)) return null
        destDir.mkdirs()
        val dest = File(destDir, take.originalName)
        move(take.file, dest)
        return dest
    }

    /** Held takes, newest first. */
    private fun takes(): List<Take> =
        dir.listFiles()?.mapNotNull { f ->
            val m = NAME.matchEntire(f.name) ?: return@mapNotNull null
            if (!f.isFile || f.length() == 0L) return@mapNotNull null
            Take(f, m.groupValues[1].toLong(), m.groupValues[2])
        }.orEmpty().sortedWith(compareByDescending<Take> { it.heldAt }.thenByDescending { it.file.name })

    private fun isExpired(take: Take) = now() - take.heldAt >= MAX_AGE_MS

    private fun move(from: File, to: File) {
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }

    companion object {
        const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
        const val MAX_KEPT = 5
        private const val PREFIX = "held-"
        private val NAME = Regex("""held-(\d+)-(.+)""")

        fun forApp(context: Context) = HeldTakes(File(context.filesDir, "cancelled"))
    }
}
