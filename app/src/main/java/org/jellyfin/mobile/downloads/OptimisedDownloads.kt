package org.jellyfin.mobile.downloads

import android.net.Uri
import androidx.core.net.toUri
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.model.api.MediaSourceInfo
import timber.log.Timber
import java.io.IOException
import java.util.UUID

/**
 * Everything specific to downloading the server's optimised rendition of an item rather than the
 * item's own file, kept together so that the behaviour can be found and changed in one place.
 */
object OptimisedDownloads {
    /**
     * What the app does when the user asked for an optimised download but the server has no
     * rendition for that item, so `Items/{id}/Download/Optimised` answers 404.
     *
     * This is a policy decision rather than a technical one, and it is deliberately expressed in
     * exactly one place. Change [MISSING_RENDITION] to switch behaviour; nothing else needs
     * touching.
     */
    enum class MissingRendition {
        /**
         * Fail the download and surface the error. The user asked for the optimised file
         * specifically, so quietly handing them something else - potentially a very large original,
         * over mobile data - is worse than telling them it is not available.
         *
         * This matches the server, which deliberately splits the two endpoints:
         * `Items/{id}/Download` falls through to the original, `Items/{id}/Download/Optimised` does
         * not.
         *
         * The web client substitutes the original only after asking, which is the better answer
         * where a dialog is possible. It is not possible here: this runs in a background worker,
         * long after the request, so the choice is between failing and substituting silently.
         * The web client also asks before it ever reaches this code, so in practice a download
         * arriving here with no rendition means one disappeared in between - which is a genuine
         * error rather than a routine miss.
         */
        FAIL,

        /**
         * Download the original instead, so the user always ends up with a playable file.
         */
        FALL_BACK,
    }

    /**
     * The behaviour described by [MissingRendition]. Chosen 2026-09-22; see the values above for the
     * reasoning, and change this line to reverse it.
     */
    val MISSING_RENDITION = MissingRendition.FAIL

    /**
     * The server routes added by the downloads-location feature. They are not part of the generated
     * SDK, so they are requested by path.
     */
    private const val OPTIMISED_DOWNLOAD_PATH = "/Items/{itemId}/Download/Optimised"
    private const val OPTIMISED_MEDIA_INFO_PATH = "/Items/{itemId}/Download/Optimised/MediaInfo"
    private const val DOWNLOAD_MEDIA_INFO_PATH = "/Items/{itemId}/Download/MediaInfo"

    /**
     * Where the main file of a download comes from, and what it is.
     *
     * @property url The URL to fetch it from.
     * @property mediaSource The server's description of that file when it is not the item's own
     * file, or null when the item already describes it.
     */
    data class MainFile(val url: Uri, val mediaSource: MediaSourceInfo?)

    /**
     * What a server said about the file a route would serve in place of the item's own.
     */
    private sealed interface Rendition {
        /** There is none: an optimised route has nothing, or the plain route serves the item's own file. */
        data object None : Rendition

        /**
         * There is one. [mediaSource] is null only when its description could not be read, in which
         * case the file is still downloaded - the right bytes with the item's description is where
         * this stood before the description existed, and failing the download would be worse.
         */
        data class Found(val mediaSource: MediaSourceInfo?) : Rendition

        /** The server answered with an error, so whether there is one is unknown. */
        data class Unanswered(val status: Int) : Rendition
    }

    /**
     * Builds the URL of an item's optimised rendition.
     */
    fun optimisedDownloadUrl(api: ApiClient, itemId: UUID): Uri =
        api.createUrl(OPTIMISED_DOWNLOAD_PATH, mapOf("itemId" to itemId)).toUri()

    /**
     * Decides which URL the main file of [download] is fetched from, and what that file is.
     *
     * Both routes are asked to describe what they would serve before any bytes move. That is what
     * lets the download be stored with the description of the file it actually got - under
     * substitution even a plain download can be a rendition, with its own name, container and
     * tracks, and an offline player selecting tracks from the original's list picks the wrong one.
     * It also confirms an optimised rendition exists, so a missing one is dealt with here rather
     * than surfacing later as a failed transfer.
     *
     * Throws [IllegalStateException] when there is no rendition and the policy is
     * [MissingRendition.FAIL]. That matters: [DownloadQueue] treats an `IOException` as transient
     * and requeues it, which would retry a permanent 404 forever, whereas any other exception marks
     * the download as errored and stops. A failure to reach the server is an `IOException` for the
     * same reason, the other way round.
     */
    suspend fun resolveMainFile(api: ApiClient, download: DownloadEntity): MainFile {
        val originalUrl = api.libraryApi.getDownloadUrl(download.itemId).toUri()

        if (!download.optimised) return MainFile(originalUrl, describe(api, DOWNLOAD_MEDIA_INFO_PATH, download.itemId))

        when (val rendition = findRendition(api, OPTIMISED_MEDIA_INFO_PATH, download.itemId)) {
            is Rendition.Found -> return MainFile(optimisedDownloadUrl(api, download.itemId), rendition.mediaSource)
            // Treated as transient, the same as an error on the transfer itself would be.
            is Rendition.Unanswered -> throw IOException(
                "Could not look up the optimised download for item ${download.itemId}: ${rendition.status}",
            )
            Rendition.None -> Unit
        }

        return when (MISSING_RENDITION) {
            MissingRendition.FAIL -> error("No optimised version available for item ${download.itemId}")
            MissingRendition.FALL_BACK -> MainFile(originalUrl, describe(api, DOWNLOAD_MEDIA_INFO_PATH, download.itemId))
        }
    }

    /**
     * Describes what the plain route would serve in place of the item's own file, or null when it
     * would serve the item's own.
     *
     * A server that cannot answer - one without the downloads feature, which has no such route and
     * says 404 - is taken as serving the item's own file, which is what such a server does. Nothing
     * here may stop a plain download that would otherwise succeed.
     */
    private suspend fun describe(api: ApiClient, path: String, itemId: UUID): MediaSourceInfo? =
        when (val rendition = findRendition(api, path, itemId)) {
            is Rendition.Found -> rendition.mediaSource
            is Rendition.Unanswered -> {
                Timber.w("Could not look up the download for item %s: %d", itemId, rendition.status)
                null
            }
            Rendition.None -> null
        }

    private suspend fun findRendition(api: ApiClient, path: String, itemId: UUID): Rendition {
        val response = try {
            api.request(HttpMethod.GET, path, mapOf("itemId" to itemId))
        } catch (e: InvalidStatusException) {
            // 404 is an answer rather than a failure: no rendition, or a server that predates the route.
            return if (e.status == HTTP_NOT_FOUND) Rendition.None else Rendition.Unanswered(e.status)
        } catch (e: ApiClientException) {
            // Not reaching the server at all is transient, and the transfer would fail the same way.
            throw IOException("Could not reach the server to look up the download for item $itemId", e)
        }

        // No content is the plain route saying it serves the item's own file.
        if (response.status == HTTP_NO_CONTENT || response.body.isEmpty()) return Rendition.None

        return try {
            Rendition.Found(ApiSerializer.json.decodeFromString<MediaSourceInfo>(response.body.decodeToString()))
        } catch (e: IllegalArgumentException) {
            // SerializationException is one of these.
            Timber.w(e, "Could not read the description of the download for item %s", itemId)
            Rendition.Found(null)
        }
    }

    private const val HTTP_NO_CONTENT = 204
    private const val HTTP_NOT_FOUND = 404
}
