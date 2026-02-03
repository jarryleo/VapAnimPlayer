package com.tencent.qgame.animplayer

import com.tencent.qgame.animplayer.cache.VapFileCache
import com.tencent.qgame.animplayer.util.SourceUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * @Author     :Leo
 * Date        :2026/2/3
 * Description :
 */

/**
 * 加载动画,可以是文件，url，asset
 */
fun AnimView.load(data: Any?, onStartRenderOnce: () -> Unit = {}) {
    if (data == null) {
        stopPlay()
        return
    }
    if (data is File) {
        startPlayForce(data, onStartRenderOnce)
        return
    }
    if (data !is String) {
        return
    }
    val isUrl = SourceUtil.isUrl(data)
    if (isUrl) {
        //读取缓存文件
        val cacheKey = VapFileCache.buildCacheKey(data)
        val cacheFile = VapFileCache.buildCacheFile(cacheKey)
        if (cacheFile.exists()) {
            startPlayForce(cacheFile, onStartRenderOnce)
            return
        }
        //下载文件并缓存
        launch(Dispatchers.IO) {
            VapManager.downLoad(data, cacheFile).collectLatest {
                if (it.isSuccessful()) {
                    it.file?.let { file ->
                        startPlayForce(file, onStartRenderOnce)
                    }
                }
            }
        }
        return
    }
    val isFilePath = SourceUtil.isFilePath(data)
    if (isFilePath) {
        startPlayForce(File(data), onStartRenderOnce)
        return
    }
    //尝试从asset中加载
    startPlayForce(data, onStartRenderOnce)
}