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
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package com.tencent.qgame.animplayer.anim

import android.graphics.Bitmap

/**
 * 动图资源: 播放前预解码的帧图集
 * atlas为所有帧按tileWidth x tileHeight分格拼接的位图(每行cols帧, 先填行后换行)
 * 渲染时按视频时钟通过frameIndexAt选帧, 仅改纹理坐标, 运行时零解码零上传
 */
class AnimImage internal constructor(
    // 帧图集位图
    val atlas: Bitmap,
    // 单帧尺寸
    val tileWidth: Int,
    val tileHeight: Int,
    // 图集每行帧数
    val cols: Int,
    // 帧数量
    val frameCount: Int,
    // 每帧起始时间ms(累计)
    val startMs: LongArray,
    // 单次循环时长ms
    val durationMs: Long
) {

    /**
     * @param timeMs 时间ms(任意值, 内部按循环时长取模)
     * @return 该时间点应显示的帧下标
     */
    fun frameIndexAt(timeMs: Long): Int {
        if (frameCount <= 1) return 0
        val t = timeMs % durationMs
        var index = startMs.binarySearch(t)
        if (index < 0) {
            index = -index - 2
        }
        return index.coerceIn(0, frameCount - 1)
    }

    fun release() {
        if (!atlas.isRecycled) {
            atlas.recycle()
        }
    }
}
