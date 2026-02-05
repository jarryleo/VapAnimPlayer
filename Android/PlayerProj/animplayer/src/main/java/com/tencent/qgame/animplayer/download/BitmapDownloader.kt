package com.tencent.qgame.animplayer.download

import android.content.Context
import android.graphics.Bitmap
import com.tencent.qgame.animplayer.bitmap.BitmapFileDecoder
import com.tencent.qgame.animplayer.bitmap.BitmapUrlDecoder
import com.tencent.qgame.animplayer.cache.BitmapCache
import com.tencent.qgame.animplayer.cache.VapFileCache
import com.tencent.qgame.animplayer.util.ALog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * @Author     :Leo
 * Date        :2024/7/2
 * Description : Bitmap下载器
 */
object BitmapDownloader {
    private val downLoadQueue = ConcurrentLinkedQueue<String>()

    suspend fun downloadBitmap(
        context: Context,
        url: String,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        val key = BitmapCache.createKey(url, reqWidth, reqHeight)
        // 从内存缓存中获取
        val cacheData = BitmapCache.INSTANCE.getData(key)
        if (cacheData != null) {
            ALog.d(
                "BitmapDownloader",
                "memory cache hit, bitmap = $cacheData, reqWidth = $reqWidth, reqHeight = $reqHeight"
            )
            return cacheData
        }
        // 从磁盘缓存中获取
        val diskData = getBitmapFromDisk(context, key, reqWidth, reqHeight)
        if (diskData != null) {
            BitmapCache.INSTANCE.putData(key, diskData)
            ALog.d(
                "BitmapDownloader",
                "disk cache hit, bitmap = $cacheData, reqWidth = $reqWidth, reqHeight = $reqHeight"
            )
            return diskData
        }
        if (downLoadQueue.contains(url)) {
            return null
        }
        ALog.d(
            "BitmapDownloader",
            "downloadBitmap url = $url, reqWidth = $reqWidth, reqHeight = $reqHeight"
        )
        downLoadQueue.add(url)
        val bitmap = withTimeoutOrNull(30_000) {
            suspendCoroutine {
                val decode = try {
                    URLDecoder.decode(url, "UTF-8")
                } catch (e: Exception) {
                    e.printStackTrace()
                    url
                }
                val urlSafe = try {
                    URL(URLDecoder.decode(decode, "UTF-8"))
                } catch (e: Exception) {
                    e.printStackTrace()
                    it.resume(null)
                    return@suspendCoroutine
                }
                val bitmap = BitmapUrlDecoder.decodeBitmapFrom(
                    urlSafe,
                    reqWidth,
                    reqHeight
                )
                if (bitmap != null) {
                    launch(Dispatchers.IO) {
                        cacheBitmapToDisk(context, key, bitmap)
                    }
                }
                it.resume(bitmap)
            }
        }
        downLoadQueue.remove(url)
        ALog.d("BitmapDownloader", "downloadBitmap bitmap = $bitmap")
        if (bitmap != null) {
            BitmapCache.INSTANCE.putData(key, bitmap)
        }
        return bitmap
    }

    private suspend fun getBitmapFromDisk(
        context: Context,
        cacheKey: String,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        val cacheFile = VapFileCache.buildCacheBitmapFile(cacheKey, context).first()
        if (!cacheFile.exists()) {
            return null
        }
        return BitmapFileDecoder.decodeBitmapFrom(cacheFile.toString(), reqWidth, reqHeight)
    }

    private suspend fun cacheBitmapToDisk(
        context: Context,
        cacheKey: String,
        bitmap: Bitmap
    ) {
        val cacheFile = VapFileCache.buildCacheBitmapFile(cacheKey, context).first()
        if (!cacheFile.exists()) {
            cacheFile.createNewFile()
        }
        cacheFile.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}