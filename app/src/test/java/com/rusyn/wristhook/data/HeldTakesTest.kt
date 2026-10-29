package com.rusyn.wristhook.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HeldTakesTest {

    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_000_000_000_000L
    private lateinit var heldDir: File
    private lateinit var recordingDir: File
    private lateinit var held: HeldTakes

    @Before fun setUp() {
        heldDir = File(tmp.root, "cancelled")
        recordingDir = tmp.newFolder("recording")
        held = HeldTakes(heldDir) { clock }
    }

    private fun take(name: String, bytes: String = "audio-$name"): File =
        File(recordingDir, name).apply { writeText(bytes) }

    @Test fun holdMovesTheFileOutOfItsOldPlace() {
        val src = take("note-1.m4a")
        val t = held.hold(src)!!
        assertFalse(src.exists())
        assertTrue(t.file.isFile)
        assertEquals(heldDir, t.file.parentFile)
        assertEquals("audio-note-1.m4a", t.file.readText())
        assertEquals("note-1.m4a", t.originalName)
        assertEquals(clock, t.heldAt)
    }

    @Test fun emptyOrMissingFilesAreNotHeld() {
        val empty = take("note-empty.m4a", bytes = "")
        assertNull(held.hold(empty))
        assertFalse(empty.exists())
        assertNull(held.hold(File(recordingDir, "nope.m4a")))
        assertNull(held.newest())
    }

    @Test fun newestIsTheMostRecentlyHeld() {
        held.hold(take("note-a.m4a")); clock += 1000
        held.hold(take("note-b.m4a")); clock += 1000
        held.hold(take("note-c.m4a"))
        assertEquals("note-c.m4a", held.newest()!!.originalName)
    }

    @Test fun newestGoesBySecondHeldNotRecordingName() {
        // A take discarded from the outbox can be older than one cancelled earlier.
        held.hold(take("note-200.m4a")); clock += 1000
        held.hold(take("note-100.m4a"))
        assertEquals("note-100.m4a", held.newest()!!.originalName)
    }

    @Test fun releaseRestoresOriginalNameAndNextNewestTakesOver() {
        held.hold(take("note-a.m4a")); clock += 1000
        val b = held.hold(take("note-b.m4a"))!!
        val out = held.release(b, recordingDir)!!
        assertEquals(File(recordingDir, "note-b.m4a"), out)
        assertEquals("audio-note-b.m4a", out.readText())
        assertFalse(b.file.exists())
        assertEquals("note-a.m4a", held.newest()!!.originalName)
        // Released once: a second release of the same take finds nothing.
        assertNull(held.release(b, recordingDir))
    }

    @Test fun takesAgeOutAfter24Hours() {
        val t = held.hold(take("note-old.m4a"))!!
        clock += HeldTakes.MAX_AGE_MS - 1
        assertNotNull(held.newest())
        clock += 1
        assertNull(held.newest())
        assertNull(held.release(t, recordingDir))
        assertEquals(1, held.prune())
        assertFalse(t.file.exists())
    }

    @Test fun pruneKeepsOnlyTheFiveNewest() {
        val all = (1..7).map { i -> held.hold(take("note-$i.m4a"))!!.also { clock += 1000 } }
        // hold() prunes as it goes, so the two oldest are already gone.
        val left = heldDir.listFiles()!!.map { it.name }.sorted()
        assertEquals(HeldTakes.MAX_KEPT, left.size)
        assertFalse(all[0].file.exists())
        assertFalse(all[1].file.exists())
        (2..6).forEach { assertTrue(all[it].file.exists()) }
        assertEquals("note-7.m4a", held.newest()!!.originalName)
    }

    @Test fun pruneDropsExpiredAndStrayFilesKeepsFresh() {
        val old = held.hold(take("note-old.m4a"))!!
        clock += HeldTakes.MAX_AGE_MS + 5
        held.hold(take("note-new.m4a"))
        // hold() already pruned the expired take.
        assertFalse(old.file.exists())
        File(heldDir, "garbage.tmp").writeText("x")
        File(heldDir, "held-${clock}-empty.m4a").writeText("")
        assertEquals(2, held.prune())
        assertEquals(listOf("note-new.m4a"), listOf(held.newest()!!.originalName))
        assertEquals(1, heldDir.listFiles()!!.size)
    }

    @Test fun survivesAFreshInstance() {
        held.hold(take("note-a.m4a")); clock += 1000
        held.hold(take("note-b.m4a"))
        // As after the app is killed and relaunched: nothing but the directory carries over.
        val reopened = HeldTakes(File(tmp.root, "cancelled")) { clock + 60_000 }
        assertEquals(0, reopened.prune())
        val newest = reopened.newest()!!
        assertEquals("note-b.m4a", newest.originalName)
        assertEquals(clock, newest.heldAt)
        assertEquals("audio-note-b.m4a", reopened.release(newest, recordingDir)!!.readText())
    }
}
