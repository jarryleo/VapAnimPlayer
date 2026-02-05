package com.tencent.qgame.animplayer.bitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 通过Url解码 Bitmap
 */
internal object BitmapUrlDecoder : BitmapDecoder<URL>() {
    private const val TIMEOUT = 30L
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT, TimeUnit.SECONDS)
            .build()
    }

    override fun onDecode(data: URL, ops: BitmapFactory.Options): Bitmap? {
        try {
            val request = Request.Builder()
                .url(data)
                .build()
            val response = okHttpClient.newCall(request).execute()
            response.body?.byteStream()?.use { stream ->
                return BitmapFactory.decodeStream(stream, null, ops)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}