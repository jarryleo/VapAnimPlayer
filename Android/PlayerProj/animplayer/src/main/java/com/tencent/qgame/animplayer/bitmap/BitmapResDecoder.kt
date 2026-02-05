package com.tencent.qgame.animplayer.bitmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 通过Asset解码 Bitmap
 */
internal class BitmapResDecoder(val context: Context) : BitmapDecoder<Int>() {

    override fun onDecode(data: Int, ops: BitmapFactory.Options): Bitmap? {
        return runCatching {
            BitmapFactory.decodeResource(context.resources, data, ops)
        }.getOrNull()
    }
}