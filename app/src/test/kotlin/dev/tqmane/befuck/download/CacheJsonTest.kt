package dev.tqmane.befuck.download

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33])
class CacheJsonTest {
    @Test fun explicitJsonNullDoesNotDiscardWholeCachedPost() {
        val json = JSONObject().put("id", "synthetic").put("captured", 123L)
            .put("own", JSONObject.NULL).put("main", JSONObject.NULL).put("caption", "null")
        val post = FeedPostMediaCache.fromJson(json)
        assertNull(post.isOwnPost); assertNull(post.isMain)
        assertEquals("null", post.caption); assertEquals("synthetic", post.postId)
    }
    @Test fun trueAndFalseStayDistinctFromUnknown() {
        val post = FeedPostMediaCache.fromJson(JSONObject().put("id", "synthetic").put("captured", 123L).put("own", false).put("main", true))
        assertEquals(false, post.isOwnPost); assertEquals(true, post.isMain)
    }
    @Test fun explicitEmptyLegacyCaptionIsNotChangedToUnknown() {
        val json = JSONObject().put("id", "empty-caption").put("captured", 123L).put("caption", "")
        assertEquals("", FeedPostMediaCache.fromJson(json).caption)
        json.put("caption", JSONObject.NULL)
        assertNull(FeedPostMediaCache.fromJson(json).caption)
    }
}
