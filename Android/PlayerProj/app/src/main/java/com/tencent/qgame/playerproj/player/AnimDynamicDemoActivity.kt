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
package com.tencent.qgame.playerproj.player

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.tencent.qgame.animplayer.AnimConfig
import com.tencent.qgame.animplayer.AnimView
import com.tencent.qgame.animplayer.inter.IAnimListener
import com.tencent.qgame.animplayer.load
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.IALog
import com.tencent.qgame.animplayer.util.ScaleType
import com.tencent.qgame.playerproj.R
import com.tencent.qgame.playerproj.databinding.ActivityAnimSimpleDemoBinding


/**
 * VAPX demo (融合特效Demo)
 * 必须使用组件里提供的工具才能生成VAPX动画
 */
class AnimDynamicDemoActivity : AppCompatActivity(), IAnimListener {

    companion object {
        private const val TAG = "AnimSimpleDemoActivity"
    }

    private val binding by lazy {
        ActivityAnimSimpleDemoBinding.inflate(layoutInflater)
    }

    // 视频信息
    data class VideoInfo(val fileName: String, val md5: String)

    private val videoInfo = VideoInfo("pk_start.mp4", "74d24e6235304e3c56b12d4867ea7d30")


    // 动画View
    private lateinit var animView: AnimView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        // 文件加载完成后会调用init方法
        init()
    }

    private fun init() {
        // 初始化日志
        initLog()
        // 初始化调试开关
        initTestView()
        // 获取动画view
        animView = binding.playerView
        // 居中（根据父布局按比例居中并全部显示s）
        animView.setScaleType(ScaleType.FIT_CENTER)
        // 注册动画监听
        animView.setAnimListener(this)
        /**
         * 开始播放主流程
         * ps: 主要流程都是对AnimView的操作，其它比如队列，或改变窗口大小等操作都不是必须的
         */
        play(videoInfo)
    }

    private fun play(videoInfo: VideoInfo) {
        val url = videoInfo.fileName
        animView.load(
            data = url,
            dynamic = {
                setImage("title", R.drawable.icon)
                setImage("avatar1", "head.png")
                setImage("avatar2", "https://picsum.photos/300/300?random=2")
                setText("nick1", "nick1")
                setText("nick2", "nick2")
                setText("ID1", "ID1")
                setText("ID2", "ID2")
            }
        )
    }

    /**
     * 视频开始回调
     */
    override fun onVideoStart() {
        Log.i(TAG, "onVideoStart")
    }

    /**
     * 视频渲染每一帧时的回调
     * @param frameIndex 帧索引
     */
    override fun onVideoRender(frameIndex: Int, config: AnimConfig?) {
    }

    /**
     * 视频播放结束(失败也会回调onComplete)
     */
    override fun onVideoComplete() {
        Log.i(TAG, "onVideoComplete")
    }

    /**
     * 播放器被销毁情况下会调用onVideoDestroy
     */
    override fun onVideoDestroy() {
        Log.i(TAG, "onVideoDestroy")
    }

    /**
     * 失败回调
     * 一次播放时可能会调用多次，建议onFailed只做错误上报
     * @param errorType 错误类型
     * @param errorMsg 错误消息
     */
    override fun onFailed(errorType: Int, errorMsg: String?) {
        Log.i(TAG, "onFailed errorType=$errorType errorMsg=$errorMsg")
    }


    private fun initLog() {
        ALog.isDebug = true
        ALog.log = object : IALog {
            override fun i(tag: String, msg: String) {
                Log.i(tag, msg)
            }

            override fun d(tag: String, msg: String) {
                Log.d(tag, msg)
            }

            override fun e(tag: String, msg: String) {
                Log.e(tag, msg)
            }

            override fun e(tag: String, msg: String, tr: Throwable) {
                Log.e(tag, msg, tr)
            }
        }
    }


    private fun initTestView() {
        binding.btnLayout.visibility = View.VISIBLE
        /**
         * 开始播放按钮
         */
        binding.btnPlay.setOnClickListener {
            play(videoInfo)
        }
        /**
         * 结束视频按钮
         */
        binding.btnStop.setOnClickListener {
            animView.stopPlay()
        }
    }
}

