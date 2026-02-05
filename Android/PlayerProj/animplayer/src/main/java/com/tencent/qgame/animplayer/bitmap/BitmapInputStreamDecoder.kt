package com.tencent.qgame.animplayer.bitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

/**
 * 通过Asset解码 Bitmap
 */
internal object BitmapInputStreamDecoder : BitmapDecoder<InputStream>() {

    override fun onDecode(data: InputStream, ops: BitmapFactory.Options): Bitmap? {
        return runCatching {
            BitmapFactory.decodeStream(data, null, ops)
        }.getOrNull()
    }
}