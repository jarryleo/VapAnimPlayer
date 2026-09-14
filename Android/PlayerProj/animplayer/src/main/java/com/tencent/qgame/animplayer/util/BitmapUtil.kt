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
package com.tencent.qgame.animplayer.util

import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
import com.tencent.qgame.animplayer.Constant
import com.tencent.qgame.animplayer.mix.Src
import com.tencent.qgame.animplayer.mix.TxtMarqueeConfig

object BitmapUtil {

    private const val TAG = "${Constant.TAG}.BitmapUtil"

    fun createEmptyBitmap() : Bitmap {
        return Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
        }
    }

    fun createTxtBitmap(src: Src, marqueeConfig: TxtMarqueeConfig): Bitmap {
        val w = src.w
        val h = src.h
        val text = src.txt
        val paint = TextPaint().apply {
            textSize = h * 0.8f
            textAlign = Paint.Align.CENTER
            style = Paint.Style.FILL
            isAntiAlias = true
            if (src.style == Src.Style.BOLD) {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            color = src.color
        }
        // 超长文字走跑马灯: 字号固定不缩放, 生成宽纹理由渲染侧滚动
        if (marqueeConfig.enabled && w > 0) {
            val textWidth = paint.measureText(text).toInt()
            if (textWidth > w && TxtMarqueeConfig.MAX_TEX_WIDTH - 2 * w > 0) {
                return createMarqueeTxtBitmap(src, text, paint, textWidth, marqueeConfig)
            }
        }
        return createStaticTxtBitmap(src, paint)
    }

    /**
     * 静态文字: 字号缩小到能放下为止(原逻辑)
     */
    private fun createStaticTxtBitmap(src: Src, paint: TextPaint): Bitmap {
        val w = src.w
        val h = src.h
        // 这里使用ALPHA_8 在opengl渲染的时候图像出现错位
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = Rect(0, 0, w, h)
        val bounds = Rect()
        var sizeR = 0.8f
        paint.textSize = h * sizeR
        val text = src.txt
        while (sizeR > 0.1f) {
            paint.getTextBounds(text, 0, text.length, bounds)
            if (bounds.width() <= rect.width()) {
                break
            }
            sizeR -= 0.1f
            paint.textSize = h * sizeR
        }
        val fontMetrics = paint.fontMetricsInt
        val top = fontMetrics.top
        val bottom = fontMetrics.bottom
        val baseline = rect.centerY() - top/2 - bottom/2

        canvas.drawText(text, rect.centerX().toFloat(), baseline.toFloat(), paint)

        return bitmap
    }

    /**
     * 跑马灯文字: 纹理布局为 [左留白src.w][文字][右留白src.w], 渲染时纹理坐标窗口(src.w宽)在纹理上平移实现滚动
     * 左右留白保证窗口取值范围始终在纹理内(不依赖GL_REPEAT), 且文字完全滚出后回绕无缝
     * 超出纹理宽度上限的文字截断加省略号
     */
    private fun createMarqueeTxtBitmap(src: Src, text: String, paint: TextPaint, textWidth: Int, config: TxtMarqueeConfig): Bitmap {
        val w = src.w
        val h = src.h
        var drawText = text
        var drawTextWidth = textWidth
        val maxTextWidth = TxtMarqueeConfig.MAX_TEX_WIDTH - 2 * w
        if (drawTextWidth > maxTextWidth) {
            drawText = TextUtils.ellipsize(text, paint, maxTextWidth.toFloat(), TextUtils.TruncateAt.END).toString()
            drawTextWidth = paint.measureText(drawText).toInt()
            ALog.i(TAG, "marquee text too long, ellipsize srcId=${src.srcId} textWidth=$textWidth->$drawTextWidth")
        }
        if (src.fitType == Src.FitType.CENTER_FULL) {
            // 跑马灯以窗口平移展示, 按FIT_XY语义处理
            ALog.i(TAG, "marquee not support CENTER_FULL, use FIT_XY srcId=${src.srcId}")
        }
        src.isMarquee = true
        src.textWidth = drawTextWidth
        src.marqueeSpeedPxPerSec = if (config.speedPxPerSec > 0f) config.speedPxPerSec else h * TxtMarqueeConfig.AUTO_SPEED_FACTOR
        src.marqueeStartHoldMs = config.startHoldMs
        src.marqueeEndHoldMs = config.endHoldMs

        val texWidth = drawTextWidth + 2 * w
        val bitmap = Bitmap.createBitmap(texWidth, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fontMetrics = paint.fontMetricsInt
        val baseline = h / 2 - fontMetrics.top / 2 - fontMetrics.bottom / 2
        // CENTER对齐: 绘制中心点 = 左留白 + 文字宽/2
        canvas.drawText(drawText, w + drawTextWidth / 2f, baseline.toFloat(), paint)

        ALog.i(TAG, "createMarqueeTxtBitmap srcId=${src.srcId} texSize=${texWidth}x$h textWidth=$drawTextWidth speed=${src.marqueeSpeedPxPerSec}px/s")
        return bitmap
    }

}