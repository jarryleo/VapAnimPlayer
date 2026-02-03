package com.tencent.qgame.animplayer.cache

import android.content.Context
import com.tencent.qgame.animplayer.util.ALog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * SVGA 缓存管理（文件缓存）
 */
object VapFileCache : CoroutineScope by MainScope() {

    private const val TAG = "VapFileCache"
    private var cacheDir: String = ""
        get() {
            if (field != "") {
                val dir = File(field)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
            }
            return field
        }


    internal fun init(context: Context) {
        if (isInitialized()) return
        launch(Dispatchers.IO) {
            cacheDir = "${context.cacheDir.absolutePath}/svga/"
            File(cacheDir).takeIf { !it.exists() }?.mkdirs()
        }
    }

    /**
     * 清理缓存
     */
    internal fun clearCache() {
        if (!isInitialized()) {
            ALog.e(TAG, "SVGACache is not init!")
            return
        }
        launch(Dispatchers.IO) {
            clearDir(cacheDir)
            ALog.i(TAG, "Clear svga cache done!")
        }
    }

    // 清除目录下的所有文件
    internal fun clearDir(path: String) {
        try {
            val dir = File(path)
            dir.takeIf { it.exists() }?.let { parentDir ->
                parentDir.listFiles()?.forEach { file ->
                    if (!file.exists()) {
                        return@forEach
                    }
                    if (file.isDirectory) {
                        clearDir(file.absolutePath)
                    }
                    file.delete()
                }
            }
        } catch (e: Exception) {
            ALog.e(TAG, "Clear svga cache path: $path fail", e)
        }
    }

    private fun isInitialized(): Boolean {
        return ("" != cacheDir) && File(cacheDir).exists()
    }

    internal fun buildCacheKey(str: String): String {
        val messageDigest = MessageDigest.getInstance("MD5")
        messageDigest.update(str.toByteArray(charset("UTF-8")))
        val digest = messageDigest.digest()
        var sb = ""
        for (b in digest) {
            sb += String.format("%02x", b)
        }
        return sb
    }

    internal fun buildCacheKey(url: URL): String = buildCacheKey(url.toString())

    internal fun buildCacheFile(cacheKey: String, context: Context? = null): File {
        if (cacheDir.isEmpty() && context != null) {
            init(context)
        }
        return File("$cacheDir$cacheKey.mp4")
    }

}