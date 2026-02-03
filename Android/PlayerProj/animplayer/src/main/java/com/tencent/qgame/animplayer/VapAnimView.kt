package com.tencent.qgame.animplayer

/*
 * Tencent is pleased to support the open source community by making vap available.
 *
 * Copyright (C) 2020 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * Licensed under the MIT License (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/MIT
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import android.graphics.SurfaceTexture
import android.media.MediaExtractor
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.TextureView
import android.widget.FrameLayout
import com.tencent.qgame.animplayer.file.FileContainer
import com.tencent.qgame.animplayer.file.IFileContainer
import com.tencent.qgame.animplayer.inter.IAnimListener
import com.tencent.qgame.animplayer.inter.IFetchResource
import com.tencent.qgame.animplayer.inter.OnResourceClickListener
import com.tencent.qgame.animplayer.mask.MaskConfig
import com.tencent.qgame.animplayer.textureview.InnerTextureView
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.IScaleType
import com.tencent.qgame.animplayer.util.ScaleType
import com.tencent.qgame.animplayer.util.ScaleTypeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

open class VapAnimView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : IAnimView, FrameLayout(context, attrs, defStyleAttr),
    TextureView.SurfaceTextureListener, CoroutineScope by MainScope() {

    companion object {
        private const val TAG = "VapAnimView"
    }

    private var player: AnimPlayer

    private val uiHandler by lazy { Handler(Looper.getMainLooper()) }
    private var surface: SurfaceTexture? = null
    private var animListener: IAnimListener? = null
    private var innerTextureView: InnerTextureView? = null
    private var lastFile: IFileContainer? = null
    private val scaleTypeUtil = ScaleTypeUtil()
    private var afterStopRunnable: Runnable? = null
    private var onStartRenderCallback: (() -> Unit)? = null

    //是否正处于强制播放流程，避免重复播放导致状态失败
    private var isForcePlayRunner = AtomicBoolean(false)

    //防止状态错误导致后续无法播放
    private var lastForcePlayTime = 0L

    // 代理监听
    private val animProxyListener by lazy {
        object : IAnimListener {

            override fun onVideoConfigReady(config: AnimConfig): Boolean {
                ALog.d(
                    TAG, "onVideoConfigReady width = ${config.width}, height = ${config.height}"
                )
                updateVideoSize(config.width, config.height)
                return animListener?.onVideoConfigReady(config) ?: super.onVideoConfigReady(config)
            }

            override fun onVideoStart() {
                ALog.d(TAG, "onVideoStart isForcePlayRunner = false")
                isForcePlayRunner.set(false)
                animListener?.onVideoStart()
            }

            override fun onVideoRender(frameIndex: Int, config: AnimConfig?) {
                animListener?.onVideoRender(frameIndex, config)
                if (onStartRenderCallback != null && frameIndex == 1) {
                    ALog.d(TAG, "onVideoRender isForcePlayRunner = false")
                    isForcePlayRunner.set(false)
                    onStartRenderCallback?.invoke()
                    onStartRenderCallback = null
                }
            }

            override fun onVideoComplete() {
                animListener?.onVideoComplete()
                ALog.d(
                    TAG,
                    "onVideoComplete player.playLoop = ${player.playLoop}, afterStopRunnable = $afterStopRunnable"
                )
                if (player.playLoop <= 0) {
                    destroy()
                } else {
                    clearView()
                    afterStopRunnable?.let {
                        postDelayed(it, 100)
                    }
                    afterStopRunnable = null
                }
            }

            override fun onVideoDestroy() {
                ALog.d(TAG, "onVideoDestroy isForcePlayRunner = false")
                isForcePlayRunner.set(false)
                animListener?.onVideoDestroy()
            }

            override fun onFailed(errorType: Int, errorMsg: String?) {
                ALog.d(TAG, "onFailed isForcePlayRunner = false")
                isForcePlayRunner.set(false)
                animListener?.onFailed(errorType, errorMsg)
            }

        }
    }

    // 保证AnimView已经布局完成才加入TextureView
    private var onSizeChangedCalled = false
    private val prepareTextureViewRunnable = Runnable {
        val lp = scaleTypeUtil.getLayoutParam(this@VapAnimView)
        if (innerTextureView == null || innerTextureView?.width != lp.width || innerTextureView?.height != lp.height) {
            removeAllViews()
            innerTextureView = InnerTextureView(context).apply {
                player = this@VapAnimView.player
                isOpaque = false
                surfaceTextureListener = this@VapAnimView
                layoutParams = lp
            }
            addView(innerTextureView, lp)
            ALog.d(
                TAG, "prepareTextureViewRunnable width = ${lp.width}, height = ${lp.height}"
            )
        }
    }


    init {
        player = AnimPlayer(this)
        player.animListener = animProxyListener
    }

    private fun updateVideoSize(width: Int, height: Int) {
        ui {
            scaleTypeUtil.setVideoSize(width, height)
            if (!onSizeChangedCalled) {
                ALog.d(TAG, "updateVideoSize onSizeChanged not called")
                return@ui
            }
            val textureView = innerTextureView
            if (textureView == null) {
                uiHandler.removeCallbacks(prepareTextureViewRunnable)
                uiHandler.post(prepareTextureViewRunnable)
                ALog.d(TAG, "updateVideoSize prepareTextureViewRunnable called")
                return@ui
            }
            scaleTypeUtil.getRealSize().let {
                if (it.first != width || it.second != height) {
                    textureView.layoutParams = scaleTypeUtil.getLayoutParam(this@VapAnimView)
                    ALog.d(TAG, "updateVideoSize width = $width height = $height")
                }
            }
        }
    }

    private fun clearView() {
        ui {
            removeAllViews()
            innerTextureView?.surfaceTextureListener = null
            innerTextureView = null
        }
    }

    /**
     * 每次播放都需要调用一次，获取视频尺寸用来设置TextureView的LayoutParams
     */
    override fun prepareTextureView() {
        if (onSizeChangedCalled) {
            ALog.d(TAG, "prepareTextureViewRunnable called")
            uiHandler.removeCallbacks(prepareTextureViewRunnable)
            uiHandler.post(prepareTextureViewRunnable)
        } else {
            ALog.d(TAG, "prepareTextureView onSizeChanged not called")
        }
    }

    override fun getSurfaceTexture(): SurfaceTexture? {
        return innerTextureView?.surfaceTexture ?: surface
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        ALog.d(TAG, "onSurfaceTextureSizeChanged $width x $height")
        player.onSurfaceTextureSizeChanged(width, height)
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        ALog.d(TAG, "onSurfaceTextureDestroyed")
        this.surface = null
        player.onSurfaceTextureDestroyed()
        return true
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        ALog.d(TAG, "onSurfaceTextureAvailable width=$width height=$height")
        this.surface = surface
        player.onSurfaceTextureAvailable(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scaleTypeUtil.setLayoutSize(w, h)
        onSizeChangedCalled = true
        // 需要保证onSizeChanged被调用
        if (innerTextureView == null) {
            prepareTextureView()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        player.isDetachedFromWindow = false
        onResume()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        player.isDetachedFromWindow = true
        onPause()
    }


    override fun setAnimListener(animListener: IAnimListener?) {
        this.animListener = animListener
    }

    override fun setFetchResource(fetchResource: IFetchResource?) {
        player.pluginManager.getMixAnimPlugin()?.resourceRequest = fetchResource
    }

    override fun setOnResourceClickListener(resourceClickListener: OnResourceClickListener?) {
        player.pluginManager.getMixAnimPlugin()?.resourceClickListener = resourceClickListener
    }

    /**
     * 兼容方案，优先保证表情显示
     */
    open fun enableAutoTxtColorFill(enable: Boolean) {
        player.pluginManager.getMixAnimPlugin()?.autoTxtColorFill = enable
    }

    override fun setLoop(playLoop: Int) {
        player.playLoop = playLoop
    }

    override fun supportMask(isSupport: Boolean, isEdgeBlur: Boolean) {
        player.supportMaskBoolean = isSupport
        player.maskEdgeBlurBoolean = isEdgeBlur
    }

    override fun updateMaskConfig(maskConfig: MaskConfig?) {
        player.updateMaskConfig(maskConfig)
    }


    @Deprecated("Compatible older version mp4, default false")
    fun enableVersion1(enable: Boolean) {
        player.enableVersion1 = enable
    }

    // 兼容老版本视频模式
    @Deprecated("Compatible older version mp4")
    fun setVideoMode(mode: Int) {
        player.videoMode = mode
    }

    override fun setFps(fps: Int) {
        ALog.d(TAG, "setFps=$fps")
        player.defaultFps = fps
    }

    override fun setScaleType(type: ScaleType) {
        scaleTypeUtil.currentScaleType = type
    }

    override fun setScaleType(scaleType: IScaleType) {
        scaleTypeUtil.scaleTypeImpl = scaleType
    }

    /**
     * @param isMute true 静音
     */
    override fun setMute(isMute: Boolean) {
        ALog.i(TAG, "set mute=$isMute")
        player.isMute = isMute
    }

    override fun startPlay(file: File) {
        try {
            val fileContainer = FileContainer(file)
            startPlay(fileContainer)
        } catch (e: Throwable) {
            animProxyListener.onFailed(
                Constant.REPORT_ERROR_TYPE_FILE_ERROR, Constant.ERROR_MSG_FILE_ERROR
            )
            animProxyListener.onVideoComplete()
        }
    }

    override fun startPlay(assetManager: AssetManager, assetsPath: String) {
        try {
            val fileContainer = CustomAssetsFileContainer(assetManager, assetsPath)
            startPlay(fileContainer)
        } catch (e: Throwable) {
            animProxyListener.onFailed(
                Constant.REPORT_ERROR_TYPE_FILE_ERROR, Constant.ERROR_MSG_FILE_ERROR
            )
            animProxyListener.onVideoComplete()
        }
    }


    override fun startPlay(fileContainer: IFileContainer) {
        if (lastFile != fileContainer) {
            lastFile?.close() //关闭上一次播放的文件流
        }
        lastFile = if (fileContainer is CustomAssetsFileContainer) {
            fileContainer.copy() //资源文件对象结束播放后不能再次播放bug
        } else {
            fileContainer
        }
        ui {
            if (visibility != VISIBLE) {
                ALog.e(TAG, "AnimView is GONE, can't play")
                return@ui
            }
            if (!player.isRunning()) {
                post { player.startPlay(fileContainer) }
                ALog.d(
                    TAG, "startPlay called $fileContainer ,player = ${player.isSurfaceAvailable}"
                )
            } else {
                ALog.d(TAG, "is running can not start")
            }
        }
    }

    /**
     * 强制播放，如果正在播放，会先停止再播放
     */
    fun startPlayForce(fileContainer: IFileContainer, onStartRenderOnce: () -> Unit = {}) {
        val now = System.currentTimeMillis()
        if (isForcePlayRunner.getAndSet(true) && now - lastForcePlayTime < 10_000) {
            ALog.d(TAG, "startPlayForce isForcePlayRunner = true")
            return
        }
        lastForcePlayTime = now
        if (player.isRunning()) {
            ALog.d(TAG, "startPlayForce called first stopPlay ${this.hashCode()}")
            afterStopRunnable = Runnable {
                onStartRenderCallback = onStartRenderOnce
                if (isAttachedToWindow) {
                    startPlay(fileContainer)
                }
            }
            stopPlay()
        } else {
            ALog.d(TAG, "startPlayForce called ${this.hashCode()}")
            onStartRenderCallback = onStartRenderOnce
            startPlay(fileContainer)
        }
    }

    /**
     * 强制播放，如果正在播放，会先停止再播放
     * @param assetsPath 资源路径
     * @param onStartRenderOnce 首帧回调，动画真实开始回调，不是初始化完成回调
     */
    fun startPlayForce(
        assetsPath: String, onStartRenderOnce: () -> Unit = {}
    ) {
        launch(Dispatchers.IO) {
            val fileContainer =
                runCatching { CustomAssetsFileContainer(context.assets, assetsPath) }.getOrNull()
            if (fileContainer != null) {
                startPlayForce(fileContainer, onStartRenderOnce)
            }
        }
    }

    /**
     * 强制播放，如果正在播放，会先停止再播放
     * @param file 动画文件
     * @param onStartRenderOnce 首帧回调，动画真实开始回调，不是初始化完成回调
     */
    fun startPlayForce(
        file: File, onStartRenderOnce: () -> Unit = {}
    ) {
        launch(Dispatchers.IO) {
            val fileContainer = runCatching { FileContainer(file) }.getOrNull()
            if (fileContainer != null) {
                startPlayForce(fileContainer, onStartRenderOnce)
            }
        }
    }


    override fun stopPlay() {
        ALog.d(TAG, "stopPlay called")
        player.stopPlay()
    }

    override fun isRunning(): Boolean {
        return player.isRunning()
    }

    override fun getRealSize(): Pair<Int, Int> {
        return scaleTypeUtil.getRealSize()
    }

    private fun destroy() {
        player.onSurfaceTextureDestroyed()
        lastFile?.close()
        lastFile = null
        clearView()
    }

    /**
     * 释放资源
     */
    fun release() {
        player.isDetachedFromWindow = true
        destroy()
        uiHandler.removeCallbacksAndMessages(null)
        cancel("release")
    }

    private fun ui(f: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) f() else uiHandler.post { f() }
    }

    fun onPause() {
        //停止播放
        stopPlay()
    }

    fun onResume() {
        // 恢复播放
        if (player.playLoop > 0) {
            lastFile?.apply {
                startPlayForce(this)
            }
        }
    }

    /**
     * 解决Assets资源暂停重播时候失败问题
     */
    class CustomAssetsFileContainer(
        private val assetManager: AssetManager,
        private val assetsPath: String
    ) :
        IFileContainer {

        companion object {
            private const val TAG = "${Constant.TAG}.FileContainer"
        }

        private val assetFd: AssetFileDescriptor = assetManager.openFd(assetsPath)
        private val assetsInputStream: AssetManager.AssetInputStream =
            assetManager.open(
                assetsPath,
                AssetManager.ACCESS_STREAMING
            ) as AssetManager.AssetInputStream

        init {
            ALog.i(TAG, "AssetsFileContainer init")
        }

        override fun setDataSource(extractor: MediaExtractor) {
            if (assetFd.declaredLength < 0) {
                extractor.setDataSource(assetFd.fileDescriptor)
            } else {
                extractor.setDataSource(
                    assetFd.fileDescriptor,
                    assetFd.startOffset,
                    assetFd.declaredLength
                )
            }
        }

        override fun startRandomRead() {
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            return assetsInputStream.read(b, off, len)
        }

        override fun skip(pos: Long) {
            assetsInputStream.skip(pos)
        }

        override fun closeRandomRead() {
            assetsInputStream.close()
        }

        override fun close() {
            assetFd.close()
            assetsInputStream.close()
        }

        fun copy(): CustomAssetsFileContainer {
            return CustomAssetsFileContainer(assetManager, assetsPath)
        }
    }
}