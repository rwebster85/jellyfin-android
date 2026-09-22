package org.jellyfin.mobile.downloads

import android.net.Uri
import androidx.core.net.toUri
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.libraryApi
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
     * The server route serving an item's optimised rendition, added by the downloads-location
     * feature. It is not part of the generated SDK, so the path is built by hand.
     */
    private const val OPTIMISED_DOWNLOAD_PATH = "/Items/%s/Download/Optimised"

    /**
     * Builds the URL of an item's optimised rendition.
     */
    fun optimisedDownloadUrl(api: ApiClient, itemId: UUID): Uri =
        api.createUrl(OPTIMISED_DOWNLOAD_PATH.format(itemId.toString())).toUri()

    /**
     * Decides which URL the main file of [download] is fetched from.
     *
     * A plain download always uses the item's own route, which the server may transparently
     * substitute. An optimised download is checked first, so that a missing rendition is dealt with
     * before any bytes move rather than surfacing later as a failed transfer.
     *
     * Throws [IllegalStateException] when there is no rendition and the policy is
     * [MissingRendition.FAIL]. That matters: [DownloadQueue] treats an `IOException` as transient
     * and requeues it, which would retry a permanent 404 forever, whereas any other exception marks
     * the download as errored and stops.
     */
    suspend fun resolveMainFileUrl(
        api: ApiClient,
        downloader: FileDownloader,
        download: DownloadEntity,
    ): Uri {
        val originalUrl = api.libraryApi.getDownloadUrl(download.itemId).toUri()

        if (!download.optimised) return originalUrl

        val optimisedUrl = optimisedDownloadUrl(api, download.itemId)
        if (downloader.exists(api, optimisedUrl)) return optimisedUrl

        return when (MISSING_RENDITION) {
            MissingRendition.FAIL -> error("No optimised version available for item ${download.itemId}")
            MissingRendition.FALL_BACK -> originalUrl
        }
    }
}
