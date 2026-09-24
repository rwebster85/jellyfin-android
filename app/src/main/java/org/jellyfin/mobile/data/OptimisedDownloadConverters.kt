package org.jellyfin.mobile.data

import androidx.room.TypeConverter
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.MediaSourceInfo

/**
 * Stores the served file's description, `DownloadEntity.mediaSource`, as JSON in one text column.
 */
class OptimisedDownloadConverters {
    @TypeConverter
    fun fromMediaSourceInfo(mediaSource: MediaSourceInfo?): String? = mediaSource?.let(Json::encodeToString)

    @TypeConverter
    fun toMediaSourceInfo(json: String?): MediaSourceInfo? = json?.let(Json::decodeFromString)
}
