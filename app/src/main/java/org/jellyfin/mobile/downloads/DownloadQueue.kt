package org.jellyfin.mobile.downloads

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.ImageFormat
import org.jellyfin.sdk.model.api.ImageType
import java.io.IOException

class DownloadQueue(
    private val context: Context,
    private val apiClientController: ApiClientController,
    private val downloadDao: DownloadDao,
    private val downloadNotificationManager: DownloadNotificationManager,
    private val storageManager: StorageManager,
    okHttpClient: OkHttpClient,
) {
    private data class QueuedFile(val file: DownloadFileEntity, val remoteUri: Uri)

    private val _downloader = FileDownloader(okHttpClient)
    private val _downloads = mutableListOf<DownloadFiles>()

    suspend fun prepare(): Boolean {
        val queuedDownloads = downloadDao.getQueuedDownloads()
        _downloads.clear()
        _downloads.addAll(queuedDownloads)
        return _downloads.any()
    }

    suspend fun process() {
        while (_downloads.any()) {
            val iterator = _downloads.iterator()
            while (iterator.hasNext()) {
                val downloadWithFiles = iterator.next()
                process(downloadWithFiles)
                iterator.remove()
            }

            // Refetch the queued downloads
            prepare()
        }
    }

    private suspend fun process(queuedDownload: DownloadFiles) {
        var downloadWithFiles = queuedDownload

        // Mark as downloading
        downloadDao.update(downloadWithFiles.download.copy(status = DownloadStatus.DOWNLOADING))
        val api = apiClientController.getApiClient(downloadWithFiles.download.serverId, downloadWithFiles.download.userId)

        try {
            // Resolved before anything is named, since the file the server will serve decides what it
            // is saved as. Every status update below writes this copy back, so it has to carry the
            // new description or the next update would put the old one back.
            val mainFile = OptimisedDownloads.resolveMainFile(api, downloadWithFiles.download)
            downloadWithFiles = downloadWithFiles.copy(
                download = downloadWithFiles.download.copy(mediaSource = mainFile.mediaSource),
            )
            downloadDao.update(downloadWithFiles.download.copy(status = DownloadStatus.DOWNLOADING))

            val queuedFiles = prepareFiles(api, downloadWithFiles, mainFile.url)

            val notificationProgressCallback = downloadNotificationManager.downloadFile(
                downloadWithFiles.download.id,
                downloadWithFiles.download.getDisplayName(context).orEmpty(),
            )

            for (queuedFile in queuedFiles) {
                download(api, queuedFile, notificationProgressCallback)
            }

            notificationProgressCallback.onEnd()
            downloadDao.update(downloadWithFiles.download.copy(status = DownloadStatus.DOWNLOADED))
        } catch (e: CancellationException) {
            // The download could've been canceled by the app, in which case we need to refresh it before making changes
            val download = downloadDao.getDownload(downloadWithFiles.download.id)
            if (download?.status == DownloadStatus.DOWNLOADING) {
                downloadDao.update(download.copy(status = DownloadStatus.QUEUED))
            }
            throw e
        } catch (e: IOException) {
            downloadDao.update(downloadWithFiles.download.copy(status = DownloadStatus.QUEUED))
            throw e
        } catch (e: Exception) {
            downloadDao.update(downloadWithFiles.download.copy(status = DownloadStatus.ERROR))
            throw e
        }
    }

    private suspend fun download(
        api: ApiClient,
        queuedFile: QueuedFile,
        progressCallback: FileDownloader.ProgressCallback,
    ) {
        val (file, remoteUri) = queuedFile

        // Verify downloaded files and skip if valid
        if (file.status == DownloadStatus.DOWNLOADED && file.size > 0) {
            val documentFile = DocumentFile.fromSingleUri(context, file.uri)
            if (documentFile?.exists() == true && documentFile.length() == file.size) return
        }

        val fileDescriptor = context.contentResolver.openFileDescriptor(file.uri, "rw")
            ?: error("Unable to open file descriptor for ${file.fileName}")

        downloadDao.updateFile(file.copy(status = DownloadStatus.DOWNLOADING))

        try {
            _downloader.downloadAndSave(
                api = api,
                from = remoteUri,
                to = fileDescriptor,
                progressCallback = progressCallback,
            )

            // Update file record with final size and status
            downloadDao.updateFile(
                file.copy(
                    size = DocumentFile.fromSingleUri(context, file.uri)?.length() ?: 0L,
                    status = DownloadStatus.DOWNLOADED,
                ),
            )
        } catch (e: IOException) {
            downloadDao.updateFile(file.copy(status = DownloadStatus.QUEUED))
            throw e
        } catch (e: Exception) {
            downloadDao.updateFile(file.copy(status = DownloadStatus.ERROR))
            throw e
        }
    }

    private suspend fun prepareFiles(api: ApiClient, downloadWithFiles: DownloadFiles, mainFileUrl: Uri): List<QueuedFile> {
        val storageLocation = storageManager.getStorageLocation()
        val itemLocation = storageLocation?.findFile(downloadWithFiles.download.path)
            ?: storageLocation?.createDirectory(downloadWithFiles.download.path)
            ?: error("Unable to find or create folder ${downloadWithFiles.download.path}")

        return buildList {
            // Add image as first item so it can be shown in UI during downloads
            preparePrimaryImageFile(api, downloadWithFiles, itemLocation)?.let(::add)

            // Add main item second as it is (often) the largest and important file
            prepareMainFile(downloadWithFiles, itemLocation, mainFileUrl).let(::add)
        }
    }

    private suspend fun prepareMainFile(
        downloadWithFiles: DownloadFiles,
        itemLocation: DocumentFile,
        mainFileUrl: Uri,
    ) = QueuedFile(
        file = createOrUpdateFile(
            filter = { it.type == DownloadFileType.ITEM },
            downloadWithFiles = downloadWithFiles,
            itemLocation = itemLocation,
            type = DownloadFileType.ITEM,
            fileName = downloadWithFiles.download.mainFileName() ?: error("Missing item path"),
        ),
        remoteUri = mainFileUrl,
    )

    private suspend fun preparePrimaryImageFile(
        api: ApiClient,
        downloadWithFiles: DownloadFiles,
        itemLocation: DocumentFile
    ): QueuedFile? = downloadWithFiles.download.item.imageTags?.get(ImageType.PRIMARY)?.let { imageTag ->
        QueuedFile(
            file = createOrUpdateFile(
                filter = { it.type == DownloadFileType.IMAGE_PRIMARY },
                downloadWithFiles = downloadWithFiles,
                itemLocation = itemLocation,
                type = DownloadFileType.IMAGE_PRIMARY,
                fileName = "primary.webp",
            ),
            remoteUri = api.imageApi.getItemImageUrl(
                itemId = downloadWithFiles.download.item.id,
                imageType = ImageType.PRIMARY,
                tag = imageTag,
                format = ImageFormat.WEBP,
            ).toUri()
        )
    }

    private suspend fun createOrUpdateFile(
        filter: (DownloadFileEntity) -> Boolean,
        downloadWithFiles: DownloadFiles,
        itemLocation: DocumentFile,
        type: DownloadFileType,
        fileName: String,
    ): DownloadFileEntity {
        var downloadFile = downloadWithFiles.files.firstOrNull(filter)

        val file = itemLocation.findFile(fileName)
            ?: itemLocation.createFile("", fileName)
            ?: error("Unable to create file $fileName")

        if (downloadFile != null) {
            // A different name means a different file: another version of the item than the one
            // downloaded before, such as the optimised copy of something first downloaded plainly.
            // The old one would otherwise be left behind unreferenced, at its full size.
            if (downloadFile.fileName != fileName) {
                itemLocation.findFile(downloadFile.fileName)?.delete()
            }

            downloadFile = downloadFile.copy(
                type = type,
                size = 0L,
                fileName = fileName,
                uri = file.uri,
                status = DownloadStatus.QUEUED,
            )
            downloadDao.updateFile(downloadFile)
            return downloadFile
        } else {
            downloadFile = DownloadFileEntity(
                downloadId = downloadWithFiles.download.id,
                type = type,
                size = 0L,
                fileName = fileName,
                uri = file.uri,
                status = DownloadStatus.QUEUED,
            )

            val id = downloadDao.insertFile(downloadFile)
            downloadFile = downloadFile.copy(id = id)
            return downloadFile
        }
    }
}
