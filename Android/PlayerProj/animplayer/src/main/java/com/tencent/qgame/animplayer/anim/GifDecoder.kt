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
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.Paint
import com.tencent.qgame.animplayer.Constant
import com.tencent.qgame.animplayer.util.ALog
import java.io.File
import java.io.FileInputStream
import kotlin.math.ceil
import kotlin.math.min

/**
 * gif解码器: 播放前把所有帧一次性解码进单张帧图集
 * 运行期按视频时钟选帧, 只改纹理坐标, 不再产生解码与纹理上传开销
 */
object GifDecoder {
    private const val TAG = "${Constant.TAG}.GifDecoder"

    // 图集单边尺寸上限(GL_MAX_TEXTURE_SIZE保守值)
    private const val MAX_ATLAS_DIM = 2048
    // 图集总像素上限(ARGB_8888约8MB)
    private const val MAX_ATLAS_PIXELS = 2048 * 1024
    // 单帧尺寸上限
    private const val MAX_TILE_DIM = 1024

    // gif文件头
    private val GIF_MAGIC = byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte())

    fun isGif(file: File): Boolean {
        val head = ByteArray(3)
        return runCatching {
            FileInputStream(file).use { it.read(head) == 3 && head.contentEquals(GIF_MAGIC) }
        }.getOrDefault(false)
    }

    fun isGif(bytes: ByteArray): Boolean {
        return bytes.size >= 3 && bytes[0] == GIF_MAGIC[0] && bytes[1] == GIF_MAGIC[1] && bytes[2] == GIF_MAGIC[2]
    }

    /**
     * 解码gif为帧图集
     * @param reqWidth 期望展示宽(src区域宽) <=0时按原图尺寸(上限1024)
     * @param reqHeight 期望展示高 <=0时按原图尺寸(上限1024)
     * @return 非gif/单帧/解析失败返回null, 由调用方走静态图首帧兜底
     */
    @Suppress("DEPRECATION")
    fun decode(bytes: ByteArray, reqWidth: Int, reqHeight: Int): AnimImage? {
        if (!isGif(bytes)) return null
        val timing = parseTiming(bytes)
        if (timing == null || timing.frameCount <= 1 || timing.durationMs <= 0L) return null
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val movieW = movie.width()
        val movieH = movie.height()
        if (movieW <= 0 || movieH <= 0) return null

        // 单帧尺寸: 贴合展示区域并保持宽高比, 不放大, 超上限截断
        val boxW = if (reqWidth > 0) reqWidth else MAX_TILE_DIM
        val boxH = if (reqHeight > 0) reqHeight else MAX_TILE_DIM
        val scale = min(1f, min(boxW.toFloat() / movieW, boxH.toFloat() / movieH))
        val tileW = ceil(movieW * scale).toInt().coerceIn(1, MAX_TILE_DIM)
        val tileH = ceil(movieH * scale).toInt().coerceIn(1, MAX_TILE_DIM)

        // 图集布局: 单行优先, 超宽换行; 帧数/像素超预算按步长抽帧(保留原始时间轴)
        val maxCols = (MAX_ATLAS_DIM / tileW).coerceAtLeast(1)
        val maxRows = (MAX_ATLAS_DIM / tileH).coerceAtLeast(1)
        val maxFrames = minOf(maxCols * maxRows, (MAX_ATLAS_PIXELS / (tileW * tileH)).coerceAtLeast(1))
        val stride = if (timing.frameCount <= maxFrames) 1 else (timing.frameCount + maxFrames - 1) / maxFrames
        val keptCount = (timing.frameCount - 1) / stride + 1
        val cols = minOf(keptCount, maxCols)
        val rows = (keptCount + cols - 1) / cols

        val atlas = Bitmap.createBitmap(cols * tileW, rows * tileH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(atlas)
        val paint = Paint().apply {
            isFilterBitmap = true
            isDither = true
        }
        val keptStart = LongArray(keptCount)
        var kept = 0
        var i = 0
        while (i < timing.frameCount) {
            keptStart[kept] = timing.startMs[i]
            canvas.save()
            canvas.translate((kept % cols) * tileW.toFloat(), (kept / cols) * tileH.toFloat())
            canvas.scale(tileW.toFloat() / movieW, tileH.toFloat() / movieH)
            // 取帧中点时间, 避免边界取到前后帧
            movie.setTime((timing.startMs[i] + timing.delaysMs[i] / 2).toInt())
            movie.draw(canvas, 0f, 0f, paint)
            canvas.restore()
            kept++
            i += stride
        }
        ALog.i(TAG, "decode gif frames=${timing.frameCount} kept=$kept stride=$stride tile=${tileW}x$tileH atlas=${atlas.width}x${atlas.height} duration=${timing.durationMs}ms")
        return AnimImage(atlas, tileW, tileH, cols, kept, keptStart, timing.durationMs)
    }

    /**
     * 解析gif每帧延时(图形控制扩展, 单位1/100s)
     * 数据异常返回null走静态兜底
     */
    private fun parseTiming(bytes: ByteArray): Timing? {
        return runCatching {
            if (bytes.size < 13) return null
            var pos = 6 // 跳过文件头 GIF87a/GIF89a
            val packed = bytes[pos + 4].toInt() and 0xFF
            pos += 7 // 逻辑屏幕描述符
            if (packed and 0x80 != 0) { // 全局颜色表
                pos += 3 * (2 shl (packed and 0x07))
            }
            val delays = ArrayList<Int>()
            var gceDelay = -1
            val size = bytes.size
            while (pos < size) {
                when (bytes[pos].toInt() and 0xFF) {
                    0x3B -> break // 结束符
                    0x21 -> { // 扩展块
                        if (pos + 2 >= size) return null
                        val label = bytes[pos + 1].toInt() and 0xFF
                        pos += 2
                        if (label == 0xF9) { // 图形控制扩展: 帧延时
                            if (pos >= size) return null
                            val blockSize = bytes[pos].toInt() and 0xFF
                            if (pos + 1 + blockSize >= size) return null
                            if (blockSize >= 4) {
                                gceDelay = (bytes[pos + 2].toInt() and 0xFF) or ((bytes[pos + 3].toInt() and 0xFF) shl 8)
                            }
                            pos += 1 + blockSize
                            if (pos < size && bytes[pos].toInt() and 0xFF == 0) pos += 1 // 子块结束符
                        } else { // 其它扩展: 跳过数据子块
                            while (pos < size) {
                                val len = bytes[pos].toInt() and 0xFF
                                pos += 1
                                if (len == 0) break
                                pos += len
                            }
                        }
                    }
                    0x2C -> { // 图像描述符: 一帧
                        if (pos + 10 > size) return null
                        val iPacked = bytes[pos + 9].toInt() and 0xFF
                        pos += 10
                        if (iPacked and 0x80 != 0) { // 局部颜色表
                            pos += 3 * (2 shl (iPacked and 0x07))
                        }
                        if (pos >= size) return null
                        pos += 1 // LZW最小编码长度
                        while (pos < size) { // 图像数据子块
                            val len = bytes[pos].toInt() and 0xFF
                            pos += 1
                            if (len == 0) break
                            pos += len
                        }
                        if (pos > size) return null
                        // 延时0或1按100ms处理(与浏览器行为一致)
                        delays.add(if (gceDelay >= 2) gceDelay * 10 else 100)
                        gceDelay = -1
                    }
                    else -> return null // 未知块, 数据异常
                }
            }
            if (delays.isEmpty()) return null
            val startMs = LongArray(delays.size)
            var acc = 0L
            for (i in delays.indices) {
                startMs[i] = acc
                acc += delays[i]
            }
            Timing(delays.toIntArray(), startMs, acc)
        }.getOrNull()
    }

    private class Timing(val delaysMs: IntArray, val startMs: LongArray, val durationMs: Long) {
        val frameCount: Int get() = delaysMs.size
    }
}
