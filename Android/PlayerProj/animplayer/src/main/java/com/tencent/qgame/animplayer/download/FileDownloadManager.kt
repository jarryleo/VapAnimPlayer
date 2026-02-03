package com.tencent.qgame.animplayer.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * @Author     :Leo
 * Date        :2025/12/29
 * Description : 基于kotlin和协程的文件下载器，使用flow进行状态管理
 */

@Suppress("Unused")
data class DownloadStatus(
    val url: String = "",
    val progress: Int = 0,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val state: DownloadState = DownloadState.QUEUED,
    val headers: Map<String, String> = emptyMap(),
    val file: File? = null,
    val exception: DownloadException? = null,
) {
    fun isSuccessful() = state == DownloadState.COMPLETED
    fun isFailed() = state == DownloadState.FAILED
    fun getFileOrNull() = file
    fun getExceptionOrNull() = exception
}

enum class DownloadState {
    QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
}

class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Suppress("Unused")
class FileDownloadManager(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder().build(),
    private val maxConcurrentDownloads: Int = 3
) {
    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val downloadStatus = ConcurrentHashMap<String, MutableStateFlow<DownloadStatus>>()

    // 启动下载
    fun download(
        url: String,
        saveFile: File,
        headers: Map<String, String> = emptyMap()
    ): Flow<DownloadStatus> {
        // 如果正在下载，则返回现有状态
        downloadStatus[url]?.let {
            return it.asStateFlow()
        }

        val status = DownloadStatus(
            url = url,
            headers = headers,
            file = saveFile,
        )
        val statusFlow = MutableStateFlow(status)
        downloadStatus[url] = statusFlow
        startDownload(statusFlow)
        return statusFlow.asStateFlow()
    }

    private fun startDownload(state: MutableStateFlow<DownloadStatus>) {
        // 控制并发数
        if (downloadJobs.size >= maxConcurrentDownloads) {
            return
        }
        val value = state.value
        val url = value.url
        val headers = value.headers
        val file = value.file ?: return
        //开始下载任务
        val job = CoroutineScope(Dispatchers.IO).launch {
            downloadInternal(url, file, headers, state)
        }
        downloadJobs[url] = job
    }

    private suspend fun CoroutineScope.downloadInternal(
        url: String,
        destination: File,
        headers: Map<String, String>,
        statusFlow: MutableStateFlow<DownloadStatus>
    ) {
        var call: Call? = null
        val shadow = destination.shadow()
        try {
            statusFlow.value = statusFlow.value.copy(state = DownloadState.DOWNLOADING)

            // 准备请求
            val request = Request.Builder()
                .url(url)
                .apply {
                    headers.forEach { (key, value) ->
                        addHeader(key, value)
                    }
                }
                .build()

            call = okHttpClient.newCall(request)
            val response = call.execute()

            if (!response.isSuccessful) {
                throw DownloadException("HTTP ${response.code}: ${response.message}")
            }

            val responseBody = response.body ?: throw DownloadException("Response body is null")

            // 获取文件总大小
            val totalBytes = responseBody.contentLength()
            if (destination.exists()) {
                if (destination.length() == totalBytes) {
                    statusFlow.value = statusFlow.value.copy(state = DownloadState.COMPLETED)
                    responseBody.close()
                    return
                } else {
                    destination.delete()
                }
            }

            if (shadow.exists()) {
                shadow.delete()
            }
            var downloadedBytes = 0L

            // 确保目标目录存在
            shadow.parentFile?.let { parent ->
                if (!parent.exists()) {
                    parent.mkdirs()
                }
            }

            FileOutputStream(shadow, false).use { output ->
                val buffer = ByteArray(8192)
                var bytesReceived = 0L
                responseBody.byteStream().use { input ->
                    while (true) {
                        // 检查是否被取消
                        ensureActive(url)

                        val byteCount = input.read(buffer)
                        if (byteCount == -1) break

                        output.write(buffer, 0, byteCount)
                        bytesReceived += byteCount
                        downloadedBytes += byteCount

                        // 更新进度
                        val progress = if (totalBytes > 0) {
                            ((downloadedBytes * 100) / totalBytes).toInt()
                        } else 0

                        statusFlow.value = DownloadStatus(
                            progress = progress,
                            totalBytes = totalBytes,
                            downloadedBytes = downloadedBytes,
                            state = DownloadState.DOWNLOADING
                        )
                    }
                }
            }

            shadow.renameTo(destination)

            statusFlow.value = DownloadStatus(
                progress = 100,
                totalBytes = totalBytes,
                downloadedBytes = downloadedBytes,
                state = DownloadState.COMPLETED
            )


        } catch (e: Exception) {
            shadow.delete()
            if (e is CancellationException) {
                statusFlow.value = statusFlow.value.copy(state = DownloadState.CANCELLED)
            } else {
                statusFlow.value = statusFlow.value.copy(state = DownloadState.FAILED)
            }
        } finally {
            call?.cancel()
            jobDone(url)
        }
    }

    private fun jobDone(url: String) {
        downloadJobs.remove(url)?.let { job ->
            if (!job.isCancelled) {
                job.cancel()
            }
        }
        downloadStatus.remove(url)
        //开始下载任务
        downloadStatus.filter {
            it.value.value.state == DownloadState.QUEUED
        }.forEach {
            startDownload(it.value)
        }
    }

    // 暂停下载
    fun pauseDownload(url: String) {
        jobDone(url)
        val status = downloadStatus[url] ?: return
        status.value = status.value.copy(state = DownloadState.PAUSED)
    }

    // 取消下载并删除文件
    fun cancelDownload(url: String, deleteFile: Boolean = true) {
        jobDone(url)
        val status = downloadStatus[url] ?: return
        status.value = status.value.copy(state = DownloadState.CANCELLED)
        if (deleteFile) {
            status.value.file?.delete()
        }
    }

    // 获取所有下载状态
    fun getAllDownloadStatus(): Map<String, DownloadStatus> {
        return downloadStatus.mapValues { it.value.value }
    }

    // 获取特定下载的状态
    fun getDownloadStatus(url: String): DownloadStatus? {
        return downloadStatus[url]?.value
    }

    // 清理资源
    fun cleanup() {
        downloadJobs.forEach { (_, job) -> job.cancel() }
        downloadJobs.clear()
        downloadStatus.clear()
    }

    private fun Response.closeQuietly() {
        try {
            close()
        } catch (ignored: Exception) {
            // 忽略关闭异常
        }
    }

    private fun ensureActive(url: String) {
        val job = downloadJobs.get(url)
        if (job?.isCancelled == true) {
            throw CancellationException("Download was cancelled")
        }
    }

    fun File.shadow(): File {
        val shadowPath = "$canonicalPath.download"
        return File(shadowPath)
    }
}
