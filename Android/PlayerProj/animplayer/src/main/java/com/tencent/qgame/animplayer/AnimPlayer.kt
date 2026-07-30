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
package com.tencent.qgame.animplayer

import com.tencent.qgame.animplayer.file.IFileContainer
import com.tencent.qgame.animplayer.inter.IAnimListener
import com.tencent.qgame.animplayer.mask.MaskConfig
import com.tencent.qgame.animplayer.plugin.AnimPluginManager
import com.tencent.qgame.animplayer.util.ALog

class AnimPlayer(val animView: IAnimView) {

    companion object {
        private const val TAG = "${Constant.TAG}.AnimPlayer"
    }

    var animListener: IAnimListener? = null
    var decoder: Decoder? = null
    var audioPlayer: AudioPlayer? = null
    var fps: Int = 0
        set(value) {
            decoder?.fps = value
            field = value
        }

    // 设置默认的fps <= 0 表示以vapc配置为准 > 0  表示以此设置为准
    var defaultFps: Int = 0
    var playLoop: Int = 0
        set(value) {
            decoder?.playLoop = value
            audioPlayer?.playLoop = value
            field = value
        }
    var supportMaskBoolean: Boolean = false
    var maskEdgeBlurBoolean: Boolean = false

    // 是否兼容老版本 默认不兼容
    var enableVersion1: Boolean = false

    // 视频模式
    var videoMode: Int = Constant.VIDEO_MODE_SPLIT_HORIZONTAL
    var isDetachedFromWindow = false
    var isSurfaceAvailable = false
    var startRunnable: Runnable? = null
    var isStartRunning = false // 启动时运行状态
    var isMute = false // 是否静音

    /**
     * 音量,范围 [0.0, 1.0]。
     * - 播放前调用:在 prepareDecoder 创建 AudioPlayer 时同步过去,下一次播放生效。
     * - 播放中调用:立即下发到当前 AudioPlayer/AudioTrack。
     */
    var volume: Float = 1f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            field = clamped
            audioPlayer?.volume = clamped
        }

    /**
     * 是否处于"等 surface"启动阶段：此时 decoder 还没真正起来，
     * stopPlay() 只是在空的 isStopReq 上盖了个戳，会被即将到来的
     * decoder.start() 同步抹掉，导致 afterStopRunnable 链路永远不触发。
     */
    val isInStartupPhase: Boolean
        get() = isStartRunning && startRunnable != null

    val configManager = AnimConfigManager(this)
    val pluginManager = AnimPluginManager(this)

    fun onSurfaceTextureDestroyed() {
        if (!isSurfaceAvailable && !isStartRunning) {
            return
        }
        isSurfaceAvailable = false
        isStartRunning = false
        decoder?.destroy()
        audioPlayer?.destroy()
    }

    fun onSurfaceTextureAvailable(width: Int, height: Int) {
        isSurfaceAvailable = true
        startRunnable?.run()
        startRunnable = null
    }


    fun onSurfaceTextureSizeChanged(width: Int, height: Int) {
        decoder?.onSurfaceSizeChanged(width, height)
    }

    fun startPlay(fileContainer: IFileContainer) {
        isStartRunning = true
        prepareDecoder()
        if (decoder?.prepareThread() == false) {
            isStartRunning = false
            decoder?.onFailed(
                Constant.REPORT_ERROR_TYPE_CREATE_THREAD,
                Constant.ERROR_MSG_CREATE_THREAD
            )
            decoder?.onVideoComplete()
            return
        }
        // 在线程中解析配置
        decoder?.renderThread?.handler?.post {
            val result =
                configManager.parseConfig(fileContainer, enableVersion1, videoMode, defaultFps)
            if (result != Constant.OK) {
                isStartRunning = false
                decoder?.onFailed(result, Constant.getErrorMsg(result))
                decoder?.onVideoComplete()
                return@post
            }
            ALog.i(TAG, "parse ${configManager.config}")
            val config = configManager.config
            // 如果是默认配置，因为信息不完整onVideoConfigReady不会被调用
            if (config != null && (config.isDefaultConfig || animListener?.onVideoConfigReady(config) == true)) {
                innerStartPlay(fileContainer)
            } else {
                ALog.i(TAG, "onVideoConfigReady return false")
            }
        }
    }

    private fun innerStartPlay(fileContainer: IFileContainer) {
        synchronized(AnimPlayer::class.java) {
            if (isSurfaceAvailable) {
                isStartRunning = false
                decoder?.start(fileContainer) //解码视频
                if (!isMute) {
                    audioPlayer?.start(fileContainer) //解码音频
                }
            } else {
                startRunnable = Runnable {
                    innerStartPlay(fileContainer)
                }
                animView.prepareTextureView()
            }
        }
    }

    fun stopPlay() {
        ALog.i(TAG, "stopPlay isInStartupPhase=$isInStartupPhase decoder.isStopReq=${decoder?.isStopReq}")
        // 启动阶段：decoder 尚未起来，stopPlay 走的 isStopReq 标志会被随后
        // decoder.start() 同步抹掉。此时直接丢弃挂起的 startRunnable 即可。
        if (isInStartupPhase) {
            cancelPendingStart()
            return
        }
        decoder?.stop()
        audioPlayer?.stop()
    }

    /**
     * 丢弃挂起的 startRunnable，让 AnimView 直接走 cancel + 新一次 startPlay 的路径。
     * 同步触发 decoder.onVideoComplete() 让 AnimView 走 destroy / clearView 清干净上一个文件容器。
     */
    fun cancelPendingStart() {
        if (!isInStartupPhase) {
            ALog.i(TAG, "cancelPendingStart called but not in startup phase, noop")
            return
        }
        ALog.i(TAG, "cancelPendingStart drop pending startRunnable")
        startRunnable = null
        isStartRunning = false
        isSurfaceAvailable = false
        // 触发完成链：AnimView.onVideoComplete 会清 lastFile / innerTextureView
        // audioPlayer 在 startup 阶段没有 start 过，无需 onVideoComplete
        decoder?.onVideoComplete()
    }

    fun isRunning(): Boolean {
        return isStartRunning // 启动过程运行状态
                || (decoder?.isRunning ?: false) // 解码过程运行状态

    }

    private fun prepareDecoder() {
        if (decoder == null) {
            decoder = HardDecoder(this).apply {
                playLoop = this@AnimPlayer.playLoop
                fps = this@AnimPlayer.fps
            }
        }
        if (audioPlayer == null) {
            audioPlayer = AudioPlayer(this).apply {
                playLoop = this@AnimPlayer.playLoop
                volume = this@AnimPlayer.volume
            }
        }
    }

    fun updateMaskConfig(maskConfig: MaskConfig?) {
        configManager.config?.maskConfig = configManager.config?.maskConfig ?: MaskConfig()
        configManager.config?.maskConfig?.safeSetMaskBitmapAndReleasePre(maskConfig?.alphaMaskBitmap)
        configManager.config?.maskConfig?.maskPositionPair = maskConfig?.maskPositionPair
        configManager.config?.maskConfig?.maskTexPair = maskConfig?.maskTexPair
    }

}