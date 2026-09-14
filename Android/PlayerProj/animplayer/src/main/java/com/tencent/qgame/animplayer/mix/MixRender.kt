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

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.tencent.qgame.animplayer.AnimConfig
import com.tencent.qgame.animplayer.Constant
import com.tencent.qgame.animplayer.PointRect
import com.tencent.qgame.animplayer.util.*

/**
 * vapx 渲染
 */
class MixRender(private val mixAnimPlugin: MixAnimPlugin) {
    companion object {
        private const val TAG = "${Constant.TAG}.MixRender"
    }
    var shader: MixShader? = null
    var vertexArray = GlFloatArray()
    var srcArray = GlFloatArray()
    var maskArray = GlFloatArray()

    /**
     * shader 与 texture初始化
     */
    fun init() {
        // shader 初始化
        shader = MixShader()
        GLES20.glDisable(GLES20.GL_DEPTH_TEST) // 关闭深度测试

        mixAnimPlugin.srcMap?.map?.values?.forEach {src->
            ALog.i(TAG, "init srcId=${src.srcId}")
            src.srcTextureId = TextureLoadUtil.loadTexture(src.bitmap)
            ALog.i(TAG, "textureProgram=${shader?.program},textureId=${src.srcTextureId}")
        }

    }

    fun renderFrame(config:AnimConfig, frame:Frame, src: Src, frameIndex:Int) {
        val videoTextureId = mixAnimPlugin.player.decoder?.render?.getExternalTexture() ?: return
        if (videoTextureId <= 0) return
        val shader = this.shader ?: return
        shader.useProgram()
        // 定点坐标
        vertexArray.setArray(VertexUtil.create(config.width, config.height, frame.frame, vertexArray.array))
        vertexArray.setVertexAttribPointer(shader.aPositionLocation)

        // src 纹理坐标
        srcArray.setArray(when {
            src.animImage != null -> genAnimCoordsArray(srcArray.array, src, config, frameIndex)
            src.isMarquee -> genMarqueeCoordsArray(srcArray.array, src, config, frameIndex)
            else -> genSrcCoordsArray(srcArray.array, frame.frame.w, frame.frame.h, src.drawWidth, src.drawHeight, src.fitType)
        })
        srcArray.setVertexAttribPointer(shader.aTextureSrcCoordinatesLocation)
        // 绑定 src纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src.srcTextureId)
        GLES20.glUniform1i(shader.uTextureSrcUnitLocation, 0)

        // mask 纹理
        maskArray.setArray(TexCoordsUtil.create(config.videoWidth, config.videoHeight, frame.mFrame, maskArray.array))
        if (frame.mt == 90) {
            maskArray.setArray(TexCoordsUtil.rotate90(maskArray.array))
        }
        maskArray.setVertexAttribPointer(shader.aTextureMaskCoordinatesLocation)
        // 绑定 mask纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES20.glUniform1i(shader.uTextureMaskUnitLocation, 1)

        // 属性处理
        if (src.srcType == Src.SrcType.TXT && mixAnimPlugin.autoTxtColorFill) { // // 文字需要颜色填充
            GLES20.glUniform1i(shader.uIsFillLocation,  1)
            val argb = transColor(src.color)
            GLES20.glUniform4f(shader.uColorLocation, argb[1], argb[2], argb[3], argb[0])
        } else {
            GLES20.glUniform1i(shader.uIsFillLocation,  0)
            GLES20.glUniform4f(shader.uColorLocation, 0f, 0f, 0f, 0f)
        }

        GLES20.glEnable(GLES20.GL_BLEND)
        // 基于源象素alpha通道值的半透明混合函数
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // draw
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisable(GLES20.GL_BLEND)

    }

    /**
     * 动图纹理坐标: 播放前gif所有帧已解码进单张帧图集纹理, 渲染时按视频时钟(frameIndex)选帧
     * 仅改纹理坐标, 运行时零解码零上传
     * 偏移量由frameIndex推导, 视频暂停则动图冻结, 动画循环时自动从头播放
     */
    private fun genAnimCoordsArray(array: FloatArray, src: Src, config: AnimConfig, frameIndex: Int): FloatArray {
        val anim = src.animImage ?: return array
        val timeMs = frameIndex * 1000L / config.fps.coerceAtLeast(1) % anim.durationMs
        val index = anim.frameIndexAt(timeMs)
        val rect = PointRect(
            index % anim.cols * anim.tileWidth,
            index / anim.cols * anim.tileHeight,
            anim.tileWidth,
            anim.tileHeight
        )
        return TexCoordsUtil.create(anim.atlas.width, anim.atlas.height, rect, array)
    }

    /**
     * 跑马灯纹理坐标: 可视窗口(src.w宽)在宽纹理上按帧号平移, 无每帧重绘与纹理上传
     * 纹理布局 [左留白src.w][文字][右留白src.w], 窗口始终落在纹理内, 不依赖GL_REPEAT
     * 单次循环时间轴: 起始停留(头部贴左边缘) -> 滚动 -> 结束停留(尾部贴右边缘) -> 滚出 -> 回绕
     * 偏移量由frameIndex推导, 视频暂停则滚动冻结, 动画循环时frameIndex归零自动从头开始
     */
    private fun genMarqueeCoordsArray(array: FloatArray, src: Src, config: AnimConfig, frameIndex: Int): FloatArray {
        val texWidth = src.bitmap?.width?.toFloat() ?: return array
        val w = src.w.toFloat()
        val textWidth = src.textWidth.toFloat()
        val speed = if (src.marqueeSpeedPxPerSec > 0f) src.marqueeSpeedPxPerSec else src.h * TxtMarqueeConfig.AUTO_SPEED_FACTOR
        val startHold = src.marqueeStartHoldMs / 1000f
        val endHold = src.marqueeEndHoldMs / 1000f
        // 头部贴左边缘 -> 尾部贴右边缘 / 尾部贴右边缘 -> 完全滚出
        val scrollHeadToTail = (textWidth - w) / speed
        val scrollOut = w / speed
        val loop = startHold + scrollHeadToTail + endHold + scrollOut
        val t = frameIndex / config.fps.coerceAtLeast(1).toFloat() % loop
        val offset = when {
            t < startHold -> w
            t - startHold < scrollHeadToTail -> w + (t - startHold) * speed
            t - startHold - scrollHeadToTail < endHold -> textWidth
            else -> textWidth + (t - startHold - scrollHeadToTail - endHold) * speed
        }
        val u0 = offset / texWidth
        val u1 = (offset + w) / texWidth
        // 倒N顺序 与 TexCoordsUtil.create保持一致
        array[0] = u0
        array[1] = 0f
        array[2] = u0
        array[3] = 1f
        array[4] = u1
        array[5] = 0f
        array[6] = u1
        array[7] = 1f
        return array
    }

    fun release(textureId: Int) {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
    }

    /**
     * CENTER_FULL 并不是严格的centerCrop(centerCrop已经前置处理)，此处主要是为防抖动做处理，复杂遮罩情况下需要固定src大小进行绘制防止抖动
     */
    private fun genSrcCoordsArray(array: FloatArray, fw: Int, fh: Int, sw: Int, sh: Int, fitType: Src.FitType): FloatArray {
        return if (fitType == Src.FitType.CENTER_FULL) {
            if (fw <= sw && fh <= sh) {
                // 中心对齐，不拉伸
                val gw = (sw - fw) / 2
                val gh = (sh - fh) / 2
                TexCoordsUtil.create(sw, sh, PointRect(gw, gh, fw, fh), array)
            } else { // centerCrop
                val fScale = fw * 1.0f / fh
                val sScale = sw * 1.0f / sh
                val srcRect = if (fScale > sScale) {
                    val w = sw
                    val x = 0
                    val h = (sw / fScale).toInt()
                    val y = (sh - h) / 2

                    PointRect(x, y, w, h)
                } else {
                    val h = sh
                    val y = 0
                    val w = (sh * fScale).toInt()
                    val x = (sw - w) / 2
                    PointRect(x, y, w, h)
                }
                TexCoordsUtil.create(sw, sh, srcRect, array)
            }
        } else { // 默认 fitXY
            TexCoordsUtil.create(fw, fh, PointRect(0, 0, fw, fh), array)
        }
    }

    private fun transColor(color: Int): FloatArray {
        val argb = FloatArray(4)
        argb[0] = (color.ushr(24) and 0x000000ff) / 255f
        argb[1] = (color.ushr(16) and 0x000000ff) / 255f
        argb[2] = (color.ushr(8) and 0x000000ff) / 255f
        argb[3] = (color and 0x000000ff) / 255f
        return argb
    }


}