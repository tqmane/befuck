package dev.tqmane.befuck.ui

import dev.tqmane.befuck.archive.ArchiveRecord
import dev.tqmane.befuck.download.*
import org.junit.Assert.*
import org.junit.Test

class PreviewModelTest {
    private val back = FeedMediaItem("https://example.invalid/back", "image/jpeg", 300, 400)
    private val front = back.copy(url = "https://example.invalid/front")
    private fun post(primary: FeedMediaItem? = back, secondary: FeedMediaItem? = front) = FeedPostMedia("one-post", null, null, primary, secondary, capturedAtMillis = 100, username = "Example")
    @Test fun cameraPagingStaysWithinThisPostAndDoesNotWrap() {
        val pages = PostMediaPages(post(), FeedMediaSelection.PRIMARY)
        assertFalse(pages.swipe(false)); assertEquals(back, pages.current!!.media)
        assertTrue(pages.swipe(true)); assertEquals(front, pages.current!!.media)
        assertFalse(pages.swipe(true)); assertEquals(1, pages.index)
        assertTrue(pages.swipe(false)); assertEquals(0, pages.index)
    }
    @Test fun tappingFrontStartsAtFrontAndMissingSideDoesNotCreateEmptyPage() {
        assertEquals(front, PostMediaPages(post(), FeedMediaSelection.SECONDARY).current!!.media)
        val single = PostMediaPages(post(null, front), FeedMediaSelection.SECONDARY)
        assertEquals(1, single.pages.size); assertEquals(front, single.current!!.media)
        assertFalse(single.swipe(false)); assertFalse(single.swipe(true))
        assertNull(PostMediaPages(post(null, null), FeedMediaSelection.PRIMARY).current)
    }
    @Test fun downloadsFreezeSideAndBlockOverlappingAllUntilCompletion() {
        val calls = arrayListOf<Pair<FeedPostMedia, FeedMediaSelection>>()
        val completions = arrayListOf<(FeedMediaDownloadResult) -> Unit>()
        val actions = PostMediaActions({ true }) { post, side, done -> calls += post to side; completions += done }
        val pages = PostMediaPages(post(), FeedMediaSelection.PRIMARY)
        assertTrue(actions.save(post(), pages.current!!.selection))
        pages.swipe(true)
        assertFalse(actions.save(post(), FeedMediaSelection.ALL))
        assertTrue(actions.save(post(), pages.current!!.selection))
        assertFalse(actions.save(post(), pages.current!!.selection))
        assertEquals(listOf(FeedMediaSelection.PRIMARY, FeedMediaSelection.SECONDARY), calls.map { it.second })
        completions.forEach { it(FeedMediaDownloadResult(true, 1, 42)) }
        assertTrue(actions.save(post(), FeedMediaSelection.ALL))
        assertFalse(actions.save(post(), FeedMediaSelection.PRIMARY))
    }
    @Test fun duplicateCompletionAndStaleAccountCannotUpdateClosedViews() {
        var valid = true; var calls = 0; var changed = 0; var finish: ((FeedMediaDownloadResult) -> Unit)? = null
        val actions = PostMediaActions({ valid }) { _, _, done -> calls++; finish = done }
        actions.watch { changed++ }
        actions.save(post(), FeedMediaSelection.PRIMARY)
        val before = changed
        valid = false; finish!!(FeedMediaDownloadResult(true, 1, 1)); finish!!(FeedMediaDownloadResult(false, 0, 0))
        assertEquals(before, changed); assertFalse(actions.save(post(), FeedMediaSelection.PRIMARY)); assertEquals(1, calls)
        actions.close(); valid = true; assertFalse(actions.save(post(), FeedMediaSelection.PRIMARY))
    }
    @Test fun videoEnumWithOpaqueUrlStaysVideoAfterArchiveRoundTrip() {
        val video = back.copy(url = "https://example.invalid/opaque?signature=fixture", mimeType = "VIDEO")
        val restored = ArchiveRecord.observe("account", post(video, video)).post()
        assertTrue(restored.primary!!.isVideo); assertEquals("video/mp4", restored.primary!!.mimeType)
        assertEquals(video.url, restored.primary!!.url)
    }
}
