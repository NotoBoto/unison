package io.github.notoboto.unison.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.notoboto.unison.UnisonApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A chapter the phone voiced before Unison Studio published it switches to the Studio version (SOURCES_DESIGN.md §12):
 * not started → replaced, listened → dropped, started → kept until opened again, then at the same word.
 * Live: «Толстый и тонкий» from Wikisource and its Studio book in the Unison catalog. Needs internet.
 */
@RunWith(AndroidJUnit4::class)
class StudioSwitchLiveTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = ctx.applicationContext as UnisonApp
    private val src = pluginSourceUrl("wikisource-ru")
    private val bookId = "Толстый и тонкий (Чехов)"
    private lateinit var id: String
    private lateinit var key: String
    private var n = 0
    private lateinit var phone: ChapterText

    @Before fun setUp() = runBlocking<Unit> {
        app.sources.repos.ensureBuiltIns()
        app.sources.repos.updates().forEach { (inst, info) -> app.sources.install(inst.repo, info) }
        assertTrue(app.repo.refreshSource(DEFAULT_SOURCE).isSuccess)
        id = app.sources.books.add("wikisource-ru", app.sources.details("wikisource-ru", bookId), app.sources.chapters("wikisource-ru", bookId))
        key = bookKey(src, id)
        app.repo.reloadLocal()
        n = app.repo.book(key)!!.chapters.single().n
        app.downloads.cancelAll(); app.repo.deleteLocal(key, n)     // nothing left from another test
        app.store.setDone(key, listOf(n), false)
        phone = voiceOnThePhone()
    }

    @After fun clean() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.player.close() }
        app.downloads.cancelAll(); app.repo.deleteLocal(key, n); app.store.setDone(key, listOf(n), false)
        TextBooks.delete(ctx, src, id); app.repo.reloadLocal()
    }

    /** What TtsWorker leaves: chNNN.json (title first, a "—" token before dialogue, even timings) and audio, version "tts". */
    private suspend fun voiceOnThePhone(): ChapterText {
        val text = app.sources.books.ensureText("wikisource-ru", id, n)
        var t = 0.0
        val paras = (listOf(text.title) + text.paras).mapIndexed { pi, p ->
            val dialogue = pi > 0 && p.trimStart().startsWith("—")
            (if (dialogue) listOf(Tok("—", t, 0)) else emptyList()) + p.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { w -> Tok(w, t, 0).also { t += 0.4 } }
        }
        val o = buildJsonObject {
            put("n", n); put("title", text.title); put("roles", buildJsonArray { add(JsonPrimitive("Рассказчик")) })
            put("paras", buildJsonArray { paras.forEach { p -> add(buildJsonArray { p.forEach { tk -> add(buildJsonArray { add(JsonPrimitive(tk.word)); add(JsonPrimitive(tk.t)); add(JsonPrimitive(0)) }) } }) } })
            put("duration", t)
        }
        app.repo.files.write(key, n, "json") { it.write(o.toString().toByteArray()) }
        app.repo.files.write(key, n, "ogg") { it.write(ByteArray(16)) }
        app.store.markDownloaded(key, n, "tts")
        app.repo.reloadLocal()
        assertNull("before the switch the phone's voicing plays", app.repo.book(key)!!.chapters.single().studio)
        return Repo.parseChapter(o.toString())
    }

    private suspend fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 90_000
        while (!cond() && System.currentTimeMillis() < until) delay(300)
        assertTrue(what, cond())
    }
    private fun version() = app.store.s.downloaded[key]?.get(n)

    @Test fun notStartedChapterIsReplacedByStudio() = runBlocking<Unit> {
        assertTrue(app.repo.refreshStudio(src, id))
        assertTrue("🎭 now", app.repo.book(key)!!.chapters.single().studio != null)
        waitFor("the Studio audio is downloaded in its place") { version()?.startsWith(StudioOverlay.STUDIO_V) == true }
    }

    @Test fun listenedChapterLosesThePhoneVoicing() = runBlocking<Unit> {
        app.store.setDone(key, listOf(n), true)
        assertTrue(app.repo.refreshStudio(src, id))
        assertNull(version())
        delay(2000)
        assertFalse("not downloaded again", app.store.isDownloaded(key, n))
        assertTrue(app.store.progress(key, n).done)
    }

    @Test fun startedChapterIsKeptThenSwitchesAtTheSameWord() = runBlocking<Unit> {
        // somewhere in the middle: the 4th word of paragraph 5
        val p = 5
        val tok = phone.paras[p].filter { PositionMap.norm(it.word) != null }[3]
        app.store.setPos(key, n, tok.t!! + 0.1, phone.duration)
        assertTrue(app.repo.refreshStudio(src, id))
        assertEquals("started: the phone's voicing stays", "tts", version())

        assertTrue(app.repo.switchStarted(app.repo.book(key)!!, n))
        val b = app.repo.book(key)!!
        val studio = app.repo.chapterText(b, n)
        val pos = app.store.progress(key, n).pos
        assertTrue("the same word in Studio's voicing at $pos",
            studio.paras[p].any { it.t == pos && PositionMap.norm(it.word) == PositionMap.norm(tok.word) })
        waitFor("and its Studio audio is downloaded") { version()?.startsWith(StudioOverlay.STUDIO_V) == true }
    }

    @Test fun openingAStartedChapterSwitchesIt() = runBlocking<Unit> {
        app.store.setPos(key, n, phone.duration / 2, phone.duration)
        assertTrue(app.repo.refreshStudio(src, id))
        assertEquals("tts", version())
        val b = app.repo.book(key)!!
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.player.open(b, n, autoplay = false) }
        waitFor("switched on opening") { version() != "tts" && app.player.now.value?.duration?.let { it > 200 } == true }
        assertTrue("🎭", app.repo.book(key)!!.chapters.single().studio != null)
        assertTrue("the position moved into Studio's timeline", app.store.progress(key, n).pos in 1.0..app.player.now.value!!.duration)
    }
}
