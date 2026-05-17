/*
 * StoryPublisher.kt
 *
 * Stories Phase 6 — client-side publish flow with a stubbed HTTP
 * call. Mirror of iOS `StoryPublisher.swift`; see that file's
 * header for the backend contract and the flip-to-real path.
 */

package app.quickink.mobile.features.stories

import app.quickink.mobile.data.story.StoryEntity
import app.quickink.mobile.data.storyitem.StoryItemEntity
import kotlinx.coroutines.delay
import kotlin.random.Random

data class StoryPublished(val slug: String, val url: String)

object StoryPublisher {

    /** When `true`, all network calls are local stubs. */
    const val STUB_BACKEND = true

    const val PUBLIC_LINK_ORIGIN = "https://quickink.app/s/"

    sealed class PublishException(msg: String) : Exception(msg) {
        object Network    : PublishException("Couldn't reach the publish service.")
        object RateLimited: PublishException("Too many publish attempts — try again in a minute.")
        class Other(msg: String) : PublishException(msg)
    }

    suspend fun publish(story: StoryEntity, items: List<StoryItemEntity>): StoryPublished {
        if (STUB_BACKEND) return publishStubbed(story, items)
        // Real backend wiring lands here — see iOS doc for the
        // shape (manifest + media refs + POST + slug return).
        throw PublishException.Other("real backend not wired yet")
    }

    suspend fun unpublish(story: StoryEntity) {
        if (STUB_BACKEND) { delay(400); return }
        throw PublishException.Other("real backend not wired yet")
    }

    private suspend fun publishStubbed(story: StoryEntity, items: List<StoryItemEntity>): StoryPublished {
        delay(800)
        val slug = story.shareSlug?.takeIf { it.isNotEmpty() } ?: generateSlug()
        return StoryPublished(slug = slug, url = PUBLIC_LINK_ORIGIN + slug)
    }

    /** 8-char base32 alphabet slug. The server is authoritative once
     *  the real backend ships; this is the client-side fallback. */
    fun generateSlug(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val sb = StringBuilder(8)
        repeat(8) { sb.append(alphabet[Random.nextInt(alphabet.length)]) }
        return sb.toString()
    }
}
