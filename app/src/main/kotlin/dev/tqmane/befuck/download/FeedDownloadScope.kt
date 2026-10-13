package dev.tqmane.befuck.download

import dev.tqmane.befuck.archive.ArchiveAccounts
import dev.tqmane.befuck.posting.BeFakeAuthHeaders
import java.io.IOException
import java.security.MessageDigest

/** A save keeps its original account/credentials and exact URLs, even after closing/swiping the viewer. */
internal class FeedDownloadScope {
    private val account = ArchiveAccounts.snapshot()
    val authorization: String? = BeFakeAuthHeaders.snapshot()["Authorization"]
    fun check() { if (account != ArchiveAccounts.snapshot()) throw IOException("Download account changed") }
    fun key(post: FeedPostMedia, selection: FeedMediaSelection, realMoji: Boolean): String {
        val text = listOf(account.uid.orEmpty(), account.generation.toString(), post.postId, selection.name, realMoji.toString(),
            post.primary?.url.orEmpty(), post.secondary?.url.orEmpty(), post.bts?.url.orEmpty()).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
