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
package com.tencent.qgame.animplayer.mix

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.tencent.qgame.animplayer.AnimConfig
import com.tencent.qgame.animplayer.AnimPlayer
import com.tencent.qgame.animplayer.Constant
import com.tencent.qgame.animplayer.inter.IFetchResource
import com.tencent.qgame.animplayer.inter.OnResourceClickListener
import com.tencent.qgame.animplayer.plugin.IAnimPlugin
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.BitmapUtil
import kotlin.math.ceil

class MixAnimPlugin(val player: AnimPlayer): IAnimPlugin {

    companion object {
        private const val TAG = "${Constant.TAG}.MixAnimPlugin"
    }
    var resourceRequest: IFetchResource? = null
    var resourceClickListener: OnResourceClickListener? = null
    var srcMap: SrcMap? = null
    var frameAll: FrameAll? = null
    var curFrameIndex = -1 // 当前帧
    private var resultCbCount = 0 // 回调次数
    private var mixRender:MixRender? = null
    private val mixTouch by lazy { MixTouch(this) }
    var autoTxtColorFill = true // 是否启动自动文字填充 默认开启
    var txtMarqueeConfig = TxtMarqueeConfig() // 文字超宽跑马灯配置

    // 同步锁
    private val lock = Object()
    private var forceStopLock = false


    override fun onConfigCreate(config: AnimConfig): Int {
        if (!config.isMix) return Constant.OK
        if (resourceRequest == null) {
            ALog.e(TAG, "IFetchResource is empty")
            // 没有设置IFetchResource 当成普通视频播放
            return Constant.OK
        }
        // step 1 parse src
        parseSrc(config)

        // step 2 parse frame
        parseFrame(config)

        // step 3 fetch resource
        fetchResourceSync()

        // step 4 生成文字bitmap
        val result = createBitmap()
        if (!result) {
            return Constant.REPORT_ERROR_TYPE_CONFIG_PLUGIN_MIX
        }

        // step 5 check resource
        ALog.i(TAG, "load resource $resultCbCount")
        srcMap?.map?.values?.forEach {
            if (it.bitmap == null) {
                ALog.e(TAG, "missing src $it")
                return Constant.REPORT_ERROR_TYPE_CONFIG_PLUGIN_MIX
            } else if (it.bitmap?.config == Bitmap.Config.ALPHA_8) {
                ALog.e(TAG, "src $it bitmap must not be ALPHA_8")
                return Constant.REPORT_ERROR_TYPE_CONFIG_PLUGIN_MIX
            }
        }
        return Constant.OK
    }

    override fun onRenderCreate() {
        if (player.configManager.config?.isMix == false) return
        ALog.i(TAG, "mix render init")
        mixRender = MixRender(this)
        mixRender?.init()
    }

    override fun onRendering(frameIndex: Int) {
        val config = player.configManager.config ?: return
        if (!config.isMix) return
        curFrameIndex = frameIndex
        val list = frameAll?.map?.get(frameIndex)?.list ?: return
        list.forEach {frame ->
            val src = srcMap?.map?.get(frame.srcId) ?: return@forEach
            mixRender?.renderFrame(config, frame, src, frameIndex)
        }
    }


    override fun onRelease() {
        destroy()
    }

    override fun onDestroy() {
        destroy()
    }

    override fun onDispatchTouchEvent(ev: MotionEvent): Boolean {
        if (player.configManager.config?.isMix == false || resourceClickListener == null) {
            return super.onDispatchTouchEvent(ev)
        }
        mixTouch.onTouchEvent(ev)?.let {resource ->
            Handler(Looper.getMainLooper()).post {
                resourceClickListener?.onClick(resource)
            }
        }
        // 只要注册监听则拦截所有事件
        return true
    }

    private fun destroy() {
        // 强制结束等待
        forceStopLockThread()
        if (player.configManager.config?.isMix == false) return
        val resources = ArrayList<Resource>()
        srcMap?.map?.values?.forEach {src ->
            mixRender?.release(src.srcTextureId)
            when(src.srcType) {
                Src.SrcType.IMG -> if (src.animImage != null) {
                    // 动图帧图集由库内部创建并释放
                    src.animImage?.release()
                } else {
                    resources.add(Resource(src))
                }
                Src.SrcType.TXT -> src.bitmap?.recycle()
                else -> {}
            }
        }
        resourceRequest?.releaseResource(resources)

        // 清理
        curFrameIndex = -1
        srcMap?.map?.clear()
        frameAll?.map?.clear()
    }

    private fun parseSrc(config: AnimConfig) {
        config.jsonConfig?.apply {
            srcMap = SrcMap(this)
        }
    }


    private fun parseFrame(config: AnimConfig) {
        config.jsonConfig?.apply {
            frameAll = FrameAll(this)
        }
        // 计算每个src的实际展示窗口宽(所有帧中最大的quad宽, 按src.h/frame.h换算到src坐标系),
        // 跑马灯判定与渲染窗口均用它, 避免quad与src宽高比不一致时文字被横向拉伸
        // 上限钳制MAX_TEX_WIDTH: 纹理宽度以它为界, 防止src.h远大于quad.h时位图超GPU纹理上限或占用过大
        val frameSets = frameAll?.map ?: return
        srcMap?.map?.values?.forEach { src ->
            var maxW = 0
            for (i in 0 until frameSets.size()) {
                frameSets.valueAt(i).list.forEach { frame ->
                    if (frame.srcId == src.srcId && frame.frame.h > 0) {
                        val windowW = ceil(frame.frame.w * src.h.toFloat() / frame.frame.h).toInt()
                        if (windowW > maxW) {
                            maxW = windowW
                        }
                    }
                }
            }
            src.displayWidth = maxW.coerceAtMost(TxtMarqueeConfig.MAX_TEX_WIDTH)
        }
    }


    private fun fetchResourceSync() {
        synchronized(lock) {
            forceStopLock = false // 开始时不会强制关闭
        }
        val time = SystemClock.elapsedRealtime()
        val totalSrc = srcMap?.map?.size ?: 0
        ALog.i(TAG, "load resource totalSrc = $totalSrc")

        resultCbCount = 0
        srcMap?.map?.values?.forEach {src ->
            if (src.srcType == Src.SrcType.IMG) {
                ALog.i(TAG, "fetch image ${src.srcId}")
                // 动图(gif)优先, 未提供时走静态图
                resourceRequest?.fetchAnimImage(Resource(src)) { anim ->
                    if (anim != null) {
                        ALog.i(TAG, "fetch anim image ${src.srcId} frames=${anim.frameCount} duration=${anim.durationMs}ms")
                        src.animImage = anim
                        src.bitmap = anim.atlas
                        resultCall()
                    } else {
                        resourceRequest?.fetchImage(Resource(src)) {
                            src.bitmap = if (it == null) {
                                ALog.e(TAG, "fetch image ${src.srcId} bitmap return null")
                                BitmapUtil.createEmptyBitmap()
                            } else it
                            ALog.i(TAG, "fetch image ${src.srcId} finish bitmap is ${it?.hashCode()}")
                            resultCall()
                        }
                    }
                }
            } else if (src.srcType == Src.SrcType.TXT) {
                ALog.i(TAG, "fetch txt ${src.srcId}")
                resourceRequest?.fetchText(Resource(src)) {
                    src.txt = it ?: ""
                    ALog.i(TAG, "fetch text ${src.srcId} finish txt is $it")
                    resultCall()
                }
            }
        }

        // 同步等待所有资源完成
        synchronized(lock) {
            while (resultCbCount < totalSrc && !forceStopLock) {
                lock.wait()
            }
        }
        ALog.i(TAG, "fetchResourceSync cost=${SystemClock.elapsedRealtime() - time}ms")
    }

    private fun forceStopLockThread() {
        synchronized(lock) {
            forceStopLock = true
            lock.notifyAll()
        }
    }

    private fun resultCall() {
        synchronized(lock) {
            resultCbCount++
            lock.notifyAll()
        }
    }

    private fun createBitmap(): Boolean {
        return try {
            srcMap?.map?.values?.forEach { src ->
                if (src.srcType == Src.SrcType.TXT) {
                    src.bitmap = BitmapUtil.createTxtBitmap(src, txtMarqueeConfig)
                }
            }
            true
        } catch (e: OutOfMemoryError) {
            ALog.e(TAG, "draw text OOM $e", e)
            false
        }
    }
}