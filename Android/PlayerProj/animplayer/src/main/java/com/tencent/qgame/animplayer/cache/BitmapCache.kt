package com.tencent.qgame.animplayer.cache

import android.graphics.Bitmap
import android.util.LruCache
import java.lang.ref.WeakReference
import kotlin.let

/**
 * @Description bitmap内存缓存
 */
class BitmapCache(private val cacheLimit: Int = limitCount) {

    private val lruCache by lazy {
        object : LruCache<String, WeakReference<Bitmap>>(cacheLimit) {
            override fun entryRemoved(
                evicted: Boolean,
                key: String?,
                oldValue: WeakReference<Bitmap>?,
                newValue: WeakReference<Bitmap>?
            ) {
                if (evicted) {
                    oldValue?.clear()
                }
            }
        }

    }

    fun getData(key: String): Bitmap? {
        val bitmap = lruCache.get(key)?.get()
        if (bitmap?.isRecycled == true){
            lruCache.remove(key)
            return null
        }
        return bitmap
    }

    fun putData(key: String, entity: Bitmap) {
        lruCache.put(key, WeakReference(entity))
    }

    /**
     * 重新设置缓存大小，只有在Android 5.0及以上才有效
     * @param limit Int
     */
    fun resizeCache(limit: Int) {
        lruCache.resize(limit)
    }

    fun clear() {
        lruCache.evictAll()
    }

    companion object {

        val INSTANCE by lazy { BitmapCache(limitCount) }

        /** 内存缓存个数 */
        var limitCount = 20
            set(value) {
                field = value
                INSTANCE.resizeCache(value)
            }

        /**
         * 拼接缓存Key所需要的字段
         */
        fun createKey(path: String, width: Int, height: Int): String {
            return "key:{path = $path width = $width height = $height}".let {
                VapFileCache.buildCacheKey(it)
            }
        }
    }
}
