package com.tencent.qgame.animplayer

import android.content.Context
import com.tencent.qgame.animplayer.cache.BitmapCache
import com.tencent.qgame.animplayer.cache.VapFileCache
import com.tencent.qgame.animplayer.download.DownloadStatus
import com.tencent.qgame.animplayer.download.FileDownloadManager
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * @Author     :Leo
 * Date        :2026/2/3
 * Description : 初始化vap
 */
object VapManager {

    /**
     * 下载器
     */
    val downloader by lazy {
        FileDownloadManager()
    }

    /**
     * 初始化，不初始也能用，就是第一个vap没有缓存
     */
    fun init(context: Context) {
        VapFileCache.init(context)
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
}