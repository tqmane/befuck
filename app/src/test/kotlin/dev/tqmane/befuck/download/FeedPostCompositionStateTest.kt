package dev.tqmane.befuck.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FeedPostCompositionStateTest {
    private class DualMediaHolder(@Suppress("unused") val primaryUrl: String)

    @Test
    fun tracksGridDetailAndFlipStateAcrossCompositionScopes() {
        val post = FeedPostMedia(
            postId = "post-grid-1",
            takenAt = null,
            caption = null,
            primary = FeedMediaItem("https://cdn.example.com/p/photo.webp?token=old", "image/webp", 100, 100),
            secondary = null,
            capturedAtMillis = 1_000L,
        )
        val dualMedia = DualMediaHolder("https://cdn.example.com/p/photo.webp?token=new")

        FeedPostCompositionState.noteGridPostSelected(post.postId)
        assertTrue(FeedPostCompositionState.hasPendingGridPostForDetail())
        assertEquals(post.postId, FeedPostCompositionState.beginGridDetailComposition(post.postId))
        assertEquals(post.postId, FeedPostCompositionState.composingGridDetailPostId())

        assertEquals(post.postId, FeedPostCompositionState.beginDualMediaComposition(dualMedia) { listOf(post) })
        FeedPostCompositionState.setComposingPrimaryDisplayed(true)
        val flipState = Any()
        FeedPostCompositionState.associateFlipStateWithComposingPost(flipState)
        assertTrue(FeedPostCompositionState.isPrimaryDisplayed(post.postId))

        FeedPostCompositionState.updatePrimaryDisplayedFromFlipState(flipState, true)
        assertFalse(FeedPostCompositionState.isPrimaryDisplayed(post.postId))

        FeedPostCompositionState.endDualMediaComposition()
        FeedPostCompositionState.endGridDetailComposition()
        assertNull(FeedPostCompositionState.composingGridDetailPostId())

        FeedPostCompositionState.endGridDetail(post.postId)
        assertFalse(FeedPostCompositionState.hasPendingGridPostForDetail())
    }
}
