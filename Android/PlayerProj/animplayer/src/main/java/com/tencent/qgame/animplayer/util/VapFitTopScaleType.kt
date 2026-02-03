package com.tencent.qgame.animplayer.util

import android.view.Gravity
import android.widget.FrameLayout

/**
 * @Author     :Leo
 * Date        :2025/3/14
 * Description : 腾讯vap自定义填充方式
 */
class VapCropScaleType(val gravity: Int = Gravity.TOP) : IScaleType {

    private var realWidth = 0
    private var realHeight = 0

    override fun getLayoutParam(
        layoutWidth: Int,
        layoutHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
        layoutParams: FrameLayout.LayoutParams
    ): FrameLayout.LayoutParams {
        val (w, h) = getCenterCropSize(layoutWidth, layoutHeight, videoWidth, videoHeight)
        if (w <= 0 && h <= 0) return layoutParams
        realWidth = w
        realHeight = h
        layoutParams.width = w
        layoutParams.height = h
        layoutParams.gravity = gravity
        return layoutParams
    }

    override fun getRealSize(): Pair<Int, Int> {
        return Pair(realWidth, realHeight)
    }

    private fun getCenterCropSize(
        layoutWidth: Int,
        layoutHeight: Int,
        videoWidth: Int,
        videoHeight: Int
    ): Pair<Int, Int> {

        val layoutRatio = layoutWidth.toFloat() / layoutHeight
        val videoRatio = videoWidth.toFloat() / videoHeight

        val realWidth: Int
        val realHeight: Int
        if (layoutRatio > videoRatio) {
            realWidth = layoutWidth
            realHeight = (realWidth / videoRatio).toInt()
        } else {
            realHeight = layoutHeight
            realWidth = (videoRatio * realHeight).toInt()
        }

        return Pair(realWidth, realHeight)
    }
}