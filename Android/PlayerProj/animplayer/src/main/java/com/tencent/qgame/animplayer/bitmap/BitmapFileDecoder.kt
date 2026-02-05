package com.tencent.qgame.animplayer.bitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 通过文件解码 Bitmap
 */
internal object BitmapFileDecoder : BitmapDecoder<String>() {

    override fun onDecode(data: String, ops: BitmapFactory.Options): Bitmap? {
        return runCatching { BitmapFactory.decodeFile(data, ops) }.getOrNull()
    }
}