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
 * Everything specific to optimised downloads, kept in one place.
 */
object OptimisedDownloads {
    /**
     * What to do when an optimised download has no optimised copy on the server. A policy choice, made in
     * one place: change [MISSING_RENDITION] to switch.
     */
    enum class MissingRendition {
        /**
         * Fail the download. Silently fetching a possibly huge original, perhaps over mobile data,
         * is worse - and this runs in a background worker, so there is no one to ask. The web client
         * has already checked, so a copy missing here disappeared in between.
         */
        FAIL,

        /**
         * Download the original instead, so the user always ends up with a playable file.
         */
        FALL_BACK,
    }

    /**
     * The behaviour described by [MissingRendition]. Change this line to reverse it.
     */
    val MISSING_RENDITION = MissingRendition.FAIL

    /**
     * The optimised download routes. Not in the generated SDK, so requested by path.
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
         * There is one. [mediaSource] is null only when its description could not be read; the file
         * is still downloaded, described by the item.
         */
        data class Found(val mediaSource: MediaSourceInfo?) : Rendition

        /** The server answered with an error, so whether there is one is unknown. */
        data class Unanswered(val status: Int) : Rendition
    }

    /**
     * Builds the URL of an item's optimised copy.
     */
    fun optimisedDownloadUrl(api: ApiClient, itemId: UUID): Uri =
        api.createUrl(OPTIMISED_DOWNLOAD_PATH, mapOf("itemId" to itemId)).toUri()

    /**
     * Decides which URL the main file of [download] is fetched from, and asks the server to describe
     * that file - under substitution even a plain download can be an optimised copy with other tracks.
     *
     * Throws [IllegalStateException] for a missing copy under [MissingRendition.FAIL], because
     * [DownloadQueue] retries an `IOException` forever but marks anything else as errored. Failing
     * to reach the server is an `IOException`, so it is retried.
     */
    suspend fun resolveMainFile(api: ApiClient, download: DownloadEntity): MainFile {
        suspend fun plainFile() = MainFile(
            api.libraryApi.getDownloadUrl(download.itemId).toUri(),
            describe(api, DOWNLOAD_MEDIA_INFO_PATH, download.itemId),
        )

        if (!download.optimised) return plainFile()

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
            MissingRendition.FALL_BACK -> plainFile()
        }
    }

    /**
     * Describes what the plain route would serve in place of the item's own file, or null when it
     * would serve the item's own. Never stops a plain download: any error reads as the item's own.
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
            // 404 is an answer rather than a failure: no optimised copy, or a server without this route.
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
