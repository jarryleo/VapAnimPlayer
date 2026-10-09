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
package com.tencent.qgame.animplayer.mix

/**
 * 文字跑马灯配置
 * 是否启用跑马灯由文字宽度自动判定: 文字宽度超过src实际展示宽度时不再缩小字号, 改为横向滚动展示
 */
class TxtMarqueeConfig {
    companion object {
        // 自适应滚动速度 = src高度 x 该系数 (px/s)
        const val AUTO_SPEED_FACTOR = 1.2f

        // 跑马灯纹理宽度上限, 纹理生成早于GL上下文无法查询MAX_TEXTURE_SIZE, 取保守值
        const val MAX_TEX_WIDTH = 2048
    }

    // 文字高度占src高度的比例, 按字形实际高度(fontMetrics top~bottom)计算, 剩余部分为上下边距
    var textHeightRatio = 0.9f

    // 滚动速度 px/s(src坐标系), <=0时按src高度自适应(1.2倍src高度/s)
    var speedPxPerSec = 0f

    // 起始停留时长ms, 文字头部贴左边缘时停留
    var startHoldMs = 500L

    // 结束停留时长ms, 文字尾部贴右边缘时停留
    var endHoldMs = 500L
}
