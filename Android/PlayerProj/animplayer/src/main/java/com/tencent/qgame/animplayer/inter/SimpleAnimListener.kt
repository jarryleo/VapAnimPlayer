package com.tencent.qgame.animplayer.inter

import com.tencent.qgame.animplayer.AnimConfig
import com.tencent.qgame.animplayer.inter.IAnimListener

/**
 * @Author     :Leo
 * Date        :2026/1/27
 * Description :
 */
open class SimpleAnimListener : IAnimListener {
    override fun onFailed(errorType: Int, errorMsg: String?) {
    }

    override fun onVideoComplete() {
    }

    override fun onVideoDestroy() {
    }

    override fun onVideoRender(
        frameIndex: Int,
        config: AnimConfig?
    ) {
    }

    override fun onVideoStart() {
    }
}