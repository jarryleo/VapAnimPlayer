### Vap 动画特效库

更新内容：
1. 优化原库切换动画需要手动停止，并延时加载下一个动画的弊端。可随意加载新动画不必管上一个动画状态。
2. 原库只能播放文件，新增可以加载 url，assets 等资源。
3. 原库动态元素替换过于麻烦，需手动转换bitmap。新增自动加载动态元素功能，图片动态元素可以加载 url, file, url, asset, bitmap 等。
4. 精简使用API，使用更简洁。
5. 动画增加文件缓存，图片动态元素增加三级缓存

how to use:

in layout:

```xml

<com.tencent.qgame.animplayer.AnimView 
    android:id="@+id/playerView"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:layout_gravity="center" />
```

in java:

```kotlin
 animView.load(
    data = url, // 动画地址 可以是 url, file, assets
    dynamic = { //动态元素 图片元素  url, file, url, asset, bitmap
        setImage("title", R.drawable.icon)
        setImage("avatar1", "head.png")
        setImage("avatar2", "https://picsum.photos/300/300?random=2")
        setText("nick1", "nick1")
        setText("nick2", "nick2")
        setText("ID1", "ID1")
        setText("ID2", "ID2")
    }
)
```
