package com.localsmsrelay.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 本项目只用到极少数几个图标。
 *
 * 引入 `material-icons-extended` 会把上万个图标全部编进 dex（实测主 dex 因此达 44 MB、
 * APK 膨胀到 27.6 MB），所以这里只按需定义缺的那一个，其余用 material-icons-core
 * 里随 Material 3 自带的图标。
 *
 * 路径数据取自 Material Symbols（Apache-2.0）。
 */
object SmsIcons {

    /** 复制。core 图标集里没有，所以在这里定义。 */
    val ContentCopy: ImageVector by lazy {
        ImageVector.Builder(
            name = "ContentCopy",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            addPath(
                pathData = addPathNodes(
                    "M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1z" +
                        "M19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7C21,5.9 20.1,5 19,5z" +
                        "M19,21H8V7h11V21z"
                ),
                fill = SolidColor(Color.Black)
            )
        }.build()
    }
}
