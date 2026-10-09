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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.tencent.qgame.animplayer.Constant
import com.tencent.qgame.animplayer.mix.Src
import com.tencent.qgame.animplayer.mix.TxtMarqueeConfig
import kotlin.math.max

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
        // 字号先按字形实际高度适配到src高度的textHeightRatio(上下留边距), 静态与跑马灯统一
        fitTextHeight(paint, h, marqueeConfig.textHeightRatio)
        // 跑马灯由文字宽度自动判定: 文字超过src实际展示窗口(max(src.w, displayWidth))才滚动,
        // 否则静态绘制, 避免不需要滚动的文字被强制滚动导致横向拉伸
        if (w > 0) {
            val textWidth = paint.measureText(text).toInt()
            val marqueeThreshold = max(w, src.displayWidth)
            if (textWidth > marqueeThreshold && TxtMarqueeConfig.MAX_TEX_WIDTH - 2 * marqueeThreshold > 0) {
                return createMarqueeTxtBitmap(src, text, paint, textWidth, marqueeConfig)
            }
        }
        return createStaticTxtBitmap(src, paint)
    }

    /**
     * 将字号缩放至字形实际高度(fontMetrics top~bottom)等于 src.h x ratio
     * textSize与字形高度非1:1(top~bottom约为textSize的1.2~1.3倍), 直接用 h x ratio 作textSize会顶满甚至裁边
     */
    private fun fitTextHeight(paint: TextPaint, srcH: Int, ratio: Float) {
        val targetRatio = ratio.coerceIn(0.1f, 1f)
        val fm = paint.fontMetrics
        val textBand = fm.bottom - fm.top
        if (textBand > 0f && srcH > 0) {
            paint.textSize = paint.textSize * (srcH * targetRatio) / textBand
        }
    }

    /**
     * 静态文字: 纹理宽取实际展示窗口宽(max(src.w, displayWidth)), 与quad宽高比一致,
     * FIT_XY整图映射到quad时横纵等比, 文字不随quad与src宽高比差异横向拉伸
     * 字号已按textHeightRatio适配(见createTxtBitmap), 超出纹理宽时按比例缩小到能放下为止
     */
    private fun createStaticTxtBitmap(src: Src, paint: TextPaint): Bitmap {
        // 宽度钳制到纹理上限, 防止病态JSON数据(src.w/displayWidth超大)生成超大位图
        val w = max(src.w, src.displayWidth).coerceAtMost(TxtMarqueeConfig.MAX_TEX_WIDTH)
        val h = src.h
        if (w <= 0 || h <= 0) {
            return createEmptyBitmap()
        }
        // 这里使用ALPHA_8 在opengl渲染的时候图像出现错位
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = Rect(0, 0, w, h)
        val bounds = Rect()
        val fittedSize = paint.textSize
        var scale = 1f
        val text = src.txt
        while (scale > 0.1f) {
            paint.textSize = fittedSize * scale
            paint.getTextBounds(text, 0, text.length, bounds)
            if (bounds.width() <= rect.width()) {
                break
            }
            scale -= 0.1f
        }
        val fontMetrics = paint.fontMetricsInt
        val top = fontMetrics.top
        val bottom = fontMetrics.bottom
        val baseline = rect.centerY() - top/2 - bottom/2

        canvas.drawText(text, rect.centerX().toFloat(), baseline.toFloat(), paint)

        return bitmap
    }

    /**
     * 跑马灯文字: 纹理布局为 [左留白margin][文字][右留白margin], 渲染时纹理坐标窗口在纹理上平移实现滚动
     * margin = max(src.w, displayWidth): 左右留白保证窗口取值范围始终在纹理内(不依赖GL_REPEAT), 且文字完全滚出后回绕无缝
     * 超出纹理宽度上限的文字截断加省略号
     */
    private fun createMarqueeTxtBitmap(src: Src, text: String, paint: TextPaint, textWidth: Int, config: TxtMarqueeConfig): Bitmap {
        val w = src.w
        val h = src.h
        var drawText = text
        var drawTextWidth = textWidth
        val margin = max(w, src.displayWidth)
        val maxTextWidth = TxtMarqueeConfig.MAX_TEX_WIDTH - 2 * margin
        if (drawTextWidth > maxTextWidth) {
            drawText = TextUtils.ellipsize(text, paint, maxTextWidth.toFloat(), TextUtils.TruncateAt.END).toString()
            drawTextWidth = paint.measureText(drawText).toInt()
            ALog.i(TAG, "marquee text too long, ellipsize srcId=${src.srcId} textWidth=$textWidth->$drawTextWidth")
        }
        if (src.fitType == Src.FitType.CENTER_FULL) {
            // 跑马灯以窗口平移展示, 窗口宽度按帧quad宽高比换算, CENTER_FULL按同语义处理
            ALog.i(TAG, "marquee with CENTER_FULL srcId=${src.srcId}")
        }
        src.isMarquee = true
        src.textWidth = drawTextWidth
        src.marqueeSpeedPxPerSec = if (config.speedPxPerSec > 0f) config.speedPxPerSec else h * TxtMarqueeConfig.AUTO_SPEED_FACTOR
        src.marqueeStartHoldMs = config.startHoldMs
        src.marqueeEndHoldMs = config.endHoldMs

        val texWidth = drawTextWidth + 2 * margin
        val bitmap = Bitmap.createBitmap(texWidth, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fontMetrics = paint.fontMetricsInt
        val baseline = h / 2 - fontMetrics.top / 2 - fontMetrics.bottom / 2
        // CENTER对齐: 绘制中心点 = 左留白 + 文字宽/2
        canvas.drawText(drawText, margin + drawTextWidth / 2f, baseline.toFloat(), paint)

        ALog.i(TAG, "createMarqueeTxtBitmap srcId=${src.srcId} texSize=${texWidth}x$h textWidth=$drawTextWidth srcW=$w displayW=${src.displayWidth} margin=$margin speed=${src.marqueeSpeedPxPerSec}px/s")
        return bitmap
    }

}