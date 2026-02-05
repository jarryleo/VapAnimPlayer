package com.tencent.qgame.animplayer

import android.graphics.Bitmap
import com.tencent.qgame.animplayer.bitmap.BitmapFileDecoder
import com.tencent.qgame.animplayer.bitmap.BitmapInputStreamDecoder
import com.tencent.qgame.animplayer.bitmap.BitmapResDecoder
import com.tencent.qgame.animplayer.cache.VapFileCache
import com.tencent.qgame.animplayer.download.BitmapDownloader
import com.tencent.qgame.animplayer.inter.IFetchResource
import com.tencent.qgame.animplayer.mix.Resource
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.SourceUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

class DynamicResource {
    internal val imageMap = mutableMapOf<String, Any>()
    internal val textMap = mutableMapOf<String, String>()

    /**
     * 设置动态图片,可以是bitmap，文件，url，assert, res
     */
    fun setImage(tag: String, image: Any) {
        imageMap[tag] = image
    }

    fun setText(tag: String, text: String) {
        textMap[tag] = text
    }
}

/**
 * 加载VAP动画
 * @param data 动画数据源, 可以是文件，url，asset
 * @param loopCount 循环次数，小于1为播放1次。
 * @param onStartRenderOnce 动画开始渲染一次的回调
 * @param dynamic 动态图片和文字, 图片可以是 bitmap，文件，url，asset
 */
fun AnimView.load(
    data: Any?,
    loopCount: Int = 0,
    onStartRenderOnce: () -> Unit = {},
    dynamic: DynamicResource.() -> Unit,
    onRelease: (resource: List<Resource>) -> Unit = {}
) {
    val dynamicResource = DynamicResource()
    dynamic.invoke(dynamicResource)
    load(
        data,
        loopCount,
        onStartRenderOnce,
        dynamicImage = {
            val tag = it.tag
            val reqWidth = it.curPoint?.w ?: 0
            val reqHeight = it.curPoint?.h ?: 0
            when (val image = dynamicResource.imageMap[tag]) {
                is Bitmap -> image
                is File -> {
                    BitmapFileDecoder.decodeBitmapFrom(image.absolutePath, reqWidth, reqHeight)
                }

                is String -> {
                    val isUrl = SourceUtil.isUrl(image)
                    val isFilePath = SourceUtil.isFilePath(image)
                    if (isUrl) { //下载网络图片
                        runBlocking {
                            BitmapDownloader.downloadBitmap(context, image, reqWidth, reqHeight)
                        }
                    } else if (isFilePath) { //加载文件
                        BitmapFileDecoder.decodeBitmapFrom(image, reqWidth, reqHeight)
                    } else { //尝试Assert
                        runCatching {
                            context.assets.open(image).use {
                                BitmapInputStreamDecoder.decodeBitmapFrom(it, reqWidth, reqHeight)
                            }
                        }.getOrNull()
                    }
                }

                is Int -> { //资源文件
                    BitmapResDecoder(context).decodeBitmapFrom(image, reqWidth, reqHeight)
                }

                else -> null
            }
        },
        dynamicText = {
            val tag = it.tag
            dynamicResource.textMap[tag]
        },
        onRelease = onRelease
    )
}

/**
 * 加载VAP动画
 * @param data 动画数据源, 可以是文件，url，asset
 * @param loopCount 循环次数，小于1为播放1次。
 * @param onStartRenderOnce 动画开始渲染一次的回调
 * @param dynamicImage 动态图片
 * @param dynamicText 动态文字
 * @param onRelease 资源释放的回调
 */
fun AnimView.load(
    data: Any?,
    loopCount: Int = 0,
    onStartRenderOnce: () -> Unit = {},
    dynamicImage: (resource: Resource) -> Bitmap? = { null },
    dynamicText: (resource: Resource) -> String? = { null },
    onRelease: (resource: List<Resource>) -> Unit = {}
) {
    setLoop(loopCount)
    setFetchResource(object : IFetchResource {
        override fun fetchImage(
            resource: Resource,
            result: (Bitmap?) -> Unit
        ) {
            result.invoke(dynamicImage(resource))
        }

        override fun fetchText(
            resource: Resource,
            result: (String?) -> Unit
        ) {
            result.invoke(dynamicText(resource))
        }

        override fun releaseResource(resources: List<Resource>) {
            onRelease.invoke(resources)
        }
    })
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
        //下载文件并缓存
        loadJob = launch(Dispatchers.IO) {
            //读取缓存文件
            val cacheKey = VapFileCache.buildCacheKey(data)
            val cacheFile = VapFileCache.buildCacheFile(cacheKey, context).first()
            if (cacheFile.exists()) {
                startPlayForce(cacheFile, onStartRenderOnce)
                return@launch
            }
            VapManager.downLoad(data, cacheFile).collectLatest {
                ALog.d("AnimView", "load url: $data, state = $it")
                if (it.isSuccessful() && isAttachedToWindow) {
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