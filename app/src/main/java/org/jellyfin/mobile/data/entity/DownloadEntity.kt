package org.jellyfin.mobile.data.entity

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSourceInfo
import java.util.UUID

@Entity(
    tableName = "download",
    indices = [Index(value = ["server_id"]), Index(value = ["user_id"]), Index(value = ["item_id"])],
    foreignKeys = [
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["server_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0L,

    @ColumnInfo(name = "server_id") val serverId: Long,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "item_id") val itemId: UUID,

    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "item") val item: BaseItemDto,

    /**
     * Whether the user asked for the server's optimised rendition rather than the item's own file.
     *
     * Stored rather than passed through, because downloads are queued and resumable: the URL is
     * chosen when the file is prepared, which can be long after the request was made.
     */
    @ColumnInfo(name = "optimised", defaultValue = "0") val optimised: Boolean = false,

    /**
     * What the server said it would actually serve, when that is a different file from the one
     * [item] describes - an optimised rendition, with its own container, name and tracks. Null when
     * the item's own file was downloaded, which [item] already describes.
     *
     * Kept apart from [item] rather than written into it, so that [item] stays the server's own
     * description of the library item: a later download of this item may be served the original,
     * and that has to be described by the original's source, not by a rendition's left behind.
     * Set each time the download is processed, since that is when the file is chosen.
     */
    @ColumnInfo(name = "media_source") val mediaSource: MediaSourceInfo? = null,

    @ColumnInfo(name = "status") val status: DownloadStatus = DownloadStatus.QUEUED,

    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "modified_at") var modifiedAt: Long = System.currentTimeMillis(),
) {
    /**
     * The source describing the file that was downloaded, which is what offline playback has to
     * select tracks from: stream indices that belong to another file map to the wrong track.
     */
    fun playbackSource(): MediaSourceInfo = mediaSource ?: item.mediaSources!!.first()

    /**
     * The name the main file is saved under, taken from the file actually served. A rendition gets
     * its own name, and so its own extension, rather than the original's - which also means a
     * different version of an item never resumes into a partial file of another.
     */
    fun mainFileName(): String? = (mediaSource?.path ?: item.path)?.replace(Regex("^.*[\\\\/]"), "")

    fun getDisplayName(context: Context) = buildString {
        val name = if (
            item.type in arrayOf(BaseItemKind.PROGRAM, BaseItemKind.RECORDING) &&
            (item.isSeries == true || !item.episodeTitle.isNullOrEmpty())
        ) {
            item.episodeTitle
        } else {
            item.name
        }

        val extraInfo = when (item.type) {
            BaseItemKind.TV_CHANNEL if !item.channelNumber.isNullOrEmpty() -> item.channelNumber
            BaseItemKind.EPISODE if item.parentIndexNumber == 0 -> context.getString(R.string.special_episode)
            in arrayOf(BaseItemKind.EPISODE, BaseItemKind.RECORDING) if item.indexNumber != null && item.parentIndexNumber != null ->
                "S${item.parentIndexNumber}:E${item.indexNumber}${item.indexNumberEnd?.let { n -> "-$n" }.orEmpty()}"

            else -> ""
        }

        listOf(item.seriesName, extraInfo, name)
            .filter { str -> !str.isNullOrEmpty() }
            .joinTo(this, separator = " - ")

        if (item.type == BaseItemKind.MOVIE && item.productionYear != null) {
            append(" (${item.productionYear})")
        } else if (item.premiereDate != null) {
            append(" (${item.premiereDate!!.year})")
        }
    }.ifEmpty { item.name }
}
