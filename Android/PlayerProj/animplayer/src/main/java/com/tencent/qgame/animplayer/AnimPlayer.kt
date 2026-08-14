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
    @Volatile
    var isSurfaceAvailable = false
    @Volatile
    var startRunnable: Runnable? = null
    @Volatile
    var isStartRunning = false // 启动时运行状态
    var isMute = false // 是否静音

    /**
     * 启动会话代际号:每次 startPlay 递增。
     * renderThread 队列中尚未执行的 parseConfig 执行前校验此值,
     * 若会话已被 tryCancelStartup / 新的 startPlay 取代则直接放弃,
     * 避免旧会话在新会话启动后被重新拉起(isStopReq 被抹掉问题)。
     */
    @Volatile
    private var startEpoch = 0

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
     * 是否处于启动阶段(decoder.start() 尚未被调用):
     * 覆盖 parseConfig 还在 renderThread 队列中、以及已解析配置但在等 surface 两种子状态。
     * 此阶段 stopPlay() 无法通过 decoder.stop() 生效(decoder 未运行,
     * isStopReq 会被随后的 decoder.start() 抹掉),必须走 tryCancelStartup()。
     * 注意:isStartRunning 在 decoder.start() 成功返回后才被清除,
     * 因此 isRunning() 在启动→解码切换瞬间不会出现 false 空窗。
     */
    val isInStartupPhase: Boolean
        get() = isStartRunning

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
        val epoch = ++startEpoch
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
            if (epoch != startEpoch) {
                ALog.i(TAG, "startPlay abandoned, stale epoch=$epoch current=$startEpoch")
                return@post
            }
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
                innerStartPlay(epoch, fileContainer)
            } else {
                ALog.i(TAG, "onVideoConfigReady return false")
            }
        }
    }

    private fun innerStartPlay(epoch: Int, fileContainer: IFileContainer) {
        synchronized(AnimPlayer::class.java) {
            // 迟到会话防护:parseConfig 越过开头 epoch 检查后,取消可能在它完成前发生。
            // 进入临界区必须重新校验,防止已取消的会话重新设置 startRunnable /
            // 拉起 decoder,导致播放错误视频或 render create fail。
            if (epoch != startEpoch) {
                ALog.i(TAG, "innerStartPlay abandoned, stale epoch=$epoch current=$startEpoch")
                return
            }
            if (isSurfaceAvailable && animView.getSurfaceTexture() != null) {
                decoder?.start(fileContainer) //解码视频
                // decoder.start() 之后再清除启动态,保证 isRunning() 在
                // 启动→解码切换瞬间不会出现 false 空窗(防止新的 startPlayForce
                // 误判为空闲走直接启动,与刚启动的会话并发解码)。
                isStartRunning = false
                if (!isMute) {
                    audioPlayer?.start(fileContainer) //解码音频
                }
            } else {
                startRunnable = Runnable {
                    innerStartPlay(epoch, fileContainer)
                }
                animView.prepareTextureView()
            }
        }
    }

    fun stopPlay() {
        ALog.i(TAG, "stopPlay isInStartupPhase=$isInStartupPhase decoder.isStopReq=${decoder?.isStopReq}")
        // 启动阶段：decoder 尚未起来，stopPlay 走的 isStopReq 标志会被随后
        // decoder.start() 同步抹掉。此时直接作废挂起的启动流程即可。
        if (tryCancelStartup()) {
            return
        }
        decoder?.stop()
        audioPlayer?.stop()
    }

    /**
     * 原子地作废一个尚未真正开始的启动会话(parseConfig 排队中 / 等 surface)。
     * 与 innerStartPlay 共用同一把锁,保证要么在 decoder.start() 之前取消成功,
     * 要么观察到会话已越过启动阶段返回 false(调用方走正常的 decoder.stop() 链路)。
     *
     * @return true 取消成功;false 会话已不在启动阶段,需要走 decoder.stop()
     */
    fun tryCancelStartup(): Boolean {
        synchronized(AnimPlayer::class.java) {
            if (!isStartRunning) {
                ALog.i(TAG, "tryCancelStartup called but not in startup phase, noop")
                return false
            }
            // 递增代际号:作废 renderThread 队列中尚未执行的 parseConfig,
            // 防止其在新会话启动后把旧视频重新拉起来。
            startEpoch++
            startRunnable = null
            isStartRunning = false
            isSurfaceAvailable = false
            ALog.i(TAG, "tryCancelStartup drop pending start, startEpoch=$startEpoch")
            // 触发完成链:AnimView.onVideoComplete 会清 lastFile / innerTextureView
            // audioPlayer 在 startup 阶段没有 start 过,无需 onVideoComplete
            decoder?.onVideoComplete()
            return true
        }
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