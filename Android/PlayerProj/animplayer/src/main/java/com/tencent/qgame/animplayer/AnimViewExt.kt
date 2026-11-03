package com.tencent.qgame.animplayer

import android.content.Context
import android.graphics.Bitmap
import com.tencent.qgame.animplayer.anim.AnimImage
import com.tencent.qgame.animplayer.anim.GifDecoder
import com.tencent.qgame.animplayer.bitmap.BitmapByteArrayDecoder
import com.tencent.qgame.animplayer.bitmap.BitmapFileDecoder
import com.tencent.qgame.animplayer.bitmap.BitmapResDecoder
import com.tencent.qgame.animplayer.cache.VapFileCache
import com.tencent.qgame.animplayer.download.DownloadState
import com.tencent.qgame.animplayer.download.DownloadStatus
import com.tencent.qgame.animplayer.inter.IFetchResource
import com.tencent.qgame.animplayer.mix.Resource
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.SourceUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * 动态图url下载的等待上限:本方法在渲染线程上被同步调用(MixAnimPlugin.fetchResourceSync
 * 会lock.wait等待全部资源)，弱网下不设上限会把整条播放管线卡死，超时按资源缺失处理
 */
private const val DYNAMIC_IMAGE_DOWNLOAD_TIMEOUT_MS = 30_000L

class DynamicResource {
    internal val imageMap = mutableMapOf<String, Any>()
    internal val textMap = mutableMapOf<String, String>()

    /**
     * 设置动态图片,可以是bitmap，文件，url，assert, res
     * gif动图会自动识别并按视频时钟逐帧播放
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
    loopCount: Int? = null,
    onStartRenderOnce: () -> Unit = {},
    downloadState: (DownloadStatus) -> Unit = {},
    dynamic: DynamicResource.() -> Unit,
    onRelease: (resource: List<Resource>) -> Unit = {}
) {
    val dynamicResource = DynamicResource()
    dynamic.invoke(dynamicResource)
    // 图片解析缓存: fetchAnimImage与fetchImage共用一次解析结果
    val imageResolveCache = HashMap<String, Any?>()
    load(
        data = data,
        loopCount = loopCount,
        onStartRenderOnce = onStartRenderOnce,
        downloadState = downloadState,
        dynamicImage = { resource ->
            resolveDynamicImage(context, imageResolveCache, dynamicResource, resource)
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
 * @param dynamicImage 动态图片, 返回Bitmap或AnimImage(gif动图)
 * @param dynamicText 动态文字
 * @param onRelease 资源释放的回调
 */
fun AnimView.load(
    data: Any?,
    loopCount: Int? = null,
    onStartRenderOnce: () -> Unit = {},
    downloadState: (DownloadStatus) -> Unit = {},
    dynamicImage: (resource: Resource) -> Any? = { null },
    dynamicText: (resource: Resource) -> String? = { null },
    onRelease: (resource: List<Resource>) -> Unit = {}
) {
    loopCount?.let {
        setLoop(it.coerceAtLeast(1))
    }
    setFetchResource(object : IFetchResource {
        override fun fetchImage(
            resource: Resource,
            result: (Bitmap?) -> Unit
        ) {
            result.invoke(dynamicImage(resource) as? Bitmap)
        }

        override fun fetchAnimImage(
            resource: Resource,
            result: (AnimImage?) -> Unit
        ) {
            result.invoke(dynamicImage(resource) as? AnimImage)
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
        loadJob?.cancel("load null data")
        loadJob = null
        stopPlay()
        return downloadState(DownloadStatus(state = DownloadState.FAILED))
    }
    if (data is File) {
        loadJob?.cancel("load file source")
        loadJob = null
        startPlayForce(data, onStartRenderOnce)
        return downloadState(DownloadStatus(state = DownloadState.COMPLETED, file = data))
    }
    if (data !is String) {
        return downloadState(DownloadStatus(state = DownloadState.FAILED))
    }
    val isUrl = SourceUtil.isUrl(data)
    if (isUrl) {
        //下载文件并缓存
        loadJob?.cancel("load new url")
        loadJob = launch(Dispatchers.IO) {
            // 当前下载任务的身份标识,过期任务(已被新 load 取消/取代)不得再发起播放,
            // 否则会抢占用户最新切换的视频。
            val currentJob = coroutineContext[Job]
            //读取缓存文件
            val cacheKey = VapFileCache.buildCacheKey(data)
            val cacheFile = VapFileCache.buildCacheFile(cacheKey, context).first()
            if (cacheFile.exists()) {
                if (loadJob == currentJob) {
                    startPlayForce(cacheFile, onStartRenderOnce)
                }
                withContext(Dispatchers.Main) {
                    downloadState(DownloadStatus(state = DownloadState.COMPLETED, file = cacheFile))
                }
                return@launch
            }
            VapManager.downLoad(data, cacheFile).collectLatest {
                ALog.d("AnimView", "load url: $data, state = $it")
                withContext(Dispatchers.Main) {
                    downloadState(it)
                }
                if (it.isSuccessful() && isAttachedToWindow && loadJob == currentJob) {
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
        loadJob?.cancel("load filepath source")
        loadJob = null
        val file = File(data)
        startPlayForce(file, onStartRenderOnce)
        return downloadState(DownloadStatus(state = DownloadState.COMPLETED, file = file))
    }
    //尝试从asset中加载
    loadJob?.cancel("load asset source")
    loadJob = null
    startPlayForce(data, onStartRenderOnce)
    return downloadState(DownloadStatus(state = DownloadState.COMPLETED))
}

/**
 * 解析动态图片资源: gif动图返回AnimImage, 其它返回Bitmap
 * 同一tag一次加载内只解析一次(fetchAnimImage与fetchImage共用结果)
 */
private fun resolveDynamicImage(
    context: Context,
    cache: HashMap<String, Any?>,
    dynamicResource: DynamicResource,
    resource: Resource
): Any? {
    val tag = resource.tag
    if (cache.containsKey(tag)) {
        return cache[tag]
    }
    val reqWidth = resource.curPoint?.w ?: 0
    val reqHeight = resource.curPoint?.h ?: 0
    val resolved: Any? = when (val image = dynamicResource.imageMap[tag]) {
        is Bitmap -> image
        is File -> decodeImageFile(image, reqWidth, reqHeight)
        is Int -> decodeImageRes(context, image, reqWidth, reqHeight)
        is String -> when {
            SourceUtil.isUrl(image) -> { //下载网络图片
                runBlocking {
                    withTimeoutOrNull(DYNAMIC_IMAGE_DOWNLOAD_TIMEOUT_MS.milliseconds) {
                        downloadImageFile(context, image)
                    }?.let { decodeImageFile(it, reqWidth, reqHeight) }
                }
            }

            SourceUtil.isFilePath(image) -> decodeImageFile(File(image), reqWidth, reqHeight)
            else -> decodeImageAsset(context, image, reqWidth, reqHeight) //尝试Assert
        }

        else -> null
    }
    cache[tag] = resolved
    return resolved
}

/**
 * 图片文件解析: gif动图返回AnimImage(解析失败回退静态首帧), 其它返回Bitmap
 */
private fun decodeImageFile(file: File, reqWidth: Int, reqHeight: Int): Any? {
    if (GifDecoder.isGif(file)) {
        val bytes = runCatching { file.readBytes() }.getOrNull()
        val anim = bytes?.let { GifDecoder.decode(it, reqWidth, reqHeight) }
        if (anim != null) {
            return anim
        }
        ALog.e("AnimView", "decode gif fail, fallback static image file=${file.absolutePath}")
    }
    return BitmapFileDecoder.decodeBitmapFrom(file.absolutePath, reqWidth, reqHeight)
}

private fun decodeImageAsset(context: Context, name: String, reqWidth: Int, reqHeight: Int): Any? {
    val bytes = runCatching {
        context.assets.open(name).use { it.readBytes() }
    }.getOrNull() ?: return null
    if (GifDecoder.isGif(bytes)) {
        return GifDecoder.decode(bytes, reqWidth, reqHeight)
    }
    return BitmapByteArrayDecoder.decodeBitmapFrom(bytes, reqWidth, reqHeight)
}

private fun decodeImageRes(context: Context, resId: Int, reqWidth: Int, reqHeight: Int): Any? {
    val bytes = runCatching {
        context.resources.openRawResource(resId).use { it.readBytes() }
    }.getOrNull() ?: return null
    if (GifDecoder.isGif(bytes)) {
        return GifDecoder.decode(bytes, reqWidth, reqHeight)
    }
    return BitmapResDecoder(context).decodeBitmapFrom(resId, reqWidth, reqHeight)
}

/**
 * 下载图片原始文件(带缓存), 供gif检测与解码
 */
private suspend fun downloadImageFile(context: Context, url: String): File? {
    return runCatching {
        val cacheFile =
            VapFileCache.buildCacheBitmapFile(VapFileCache.buildCacheKey(url), context).first()
        if (!cacheFile.exists()) {
            val status =
                VapManager.downLoad(url, cacheFile).first { it.isSuccessful() || it.isFailed() }
            if (!status.isSuccessful()) {
                ALog.e("AnimView", "download image fail url=$url")
                return null
            }
        }
        cacheFile.takeIf { it.exists() }
    }.getOrNull()
}