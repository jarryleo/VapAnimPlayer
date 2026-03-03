package com.tencent.qgame.animplayer

import android.content.Context
import android.graphics.Bitmap
import com.tencent.qgame.animplayer.cache.BitmapCache
import com.tencent.qgame.animplayer.cache.VapFileCache
import com.tencent.qgame.animplayer.download.BitmapDownloadInterface
import com.tencent.qgame.animplayer.download.BitmapDownloader
import com.tencent.qgame.animplayer.download.DownloadStatus
import com.tencent.qgame.animplayer.download.FileDownloadManager
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * @Author     :Leo
 * Date        :2026/2/3
 * Description : 初始化vap
 */
object VapManager : BitmapDownloadInterface {

    /**
     * 下载器
     */
    val downloader by lazy {
        FileDownloadManager()
    }

    private var bitmapDownloader: BitmapDownloadInterface = BitmapDownloader

    /**
     * 初始化，不初始也能用，就是第一个vap没有缓存
     */
    fun init(context: Context) {
        VapFileCache.init(context)
    }

    /**
     * 设置图片下载器
     */
    fun setBitmapDownload(bitmapDownloadInterface: BitmapDownloadInterface) {
        bitmapDownloader = bitmapDownloadInterface
    }

    fun clearCache() {
        VapFileCache.clearCache()
        BitmapCache.INSTANCE.clear()
    }

    /**
     * 下载文件
     */
    fun downLoad(url: String, file: File): Flow<DownloadStatus> {
        return downloader.download(url, file)
    }

    /**
     * 下载图片并返回bitmap
     */
    override suspend fun downloadBitmap(
        context: Context,
        url: String,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        return bitmapDownloader.downloadBitmap(context, url, reqWidth, reqHeight)
    }
}