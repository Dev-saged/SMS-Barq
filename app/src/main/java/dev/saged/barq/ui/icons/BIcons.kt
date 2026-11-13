package dev.saged.barq.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Barq's own icon family: one 24dp grid, 1.8 stroke, round caps and joins.
 * Icons are tinted at the call site, so the stroke colour here is only a placeholder.
 */
object BIcons {
    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Messages by lazy {
        icon("Messages", "M7,4H17A4,4 0 0 1 21,8V13A4,4 0 0 1 17,17H11.5L7.5,20.2V17H7A4,4 0 0 1 3,13V8A4,4 0 0 1 7,4Z")
    }
    val Star by lazy {
        icon("Star", "M12,3.8L14.5,8.9L20.1,9.7L16.05,13.65L17,19.2L12,16.55L7,19.2L7.95,13.65L3.9,9.7L9.5,8.9Z")
    }
    val Archive by lazy {
        icon(
            "Archive",
            "M4,6.5A1.5,1.5 0 0 1 5.5,5H18.5A1.5,1.5 0 0 1 20,6.5V7.5A1.5,1.5 0 0 1 18.5,9H5.5A1.5,1.5 0 0 1 4,7.5Z",
            "M5.5,9V17.5A1.5,1.5 0 0 0 7,19H17A1.5,1.5 0 0 0 18.5,17.5V9",
            "M10,13H14",
        )
    }
    val Settings by lazy {
        icon(
            "Settings",
            "M4,8H13", "M17,8H20", "M4,16H7", "M11,16H20",
            "M15,6A2,2 0 1 0 15,10A2,2 0 1 0 15,6Z",
            "M9,14A2,2 0 1 0 9,18A2,2 0 1 0 9,14Z",
        )
    }
    val Edit by lazy {
        icon("Edit", "M4,20H8L19,9A2.1,2.1 0 0 0 16,6L5,17Z", "M14.5,7.5L17.5,10.5")
    }
    val Language by lazy {
        icon(
            "Language",
            "M12,4A8,8 0 1 0 12,20A8,8 0 1 0 12,4Z",
            "M12,4C9.5,6.5 9.5,17.5 12,20", "M12,4C14.5,6.5 14.5,17.5 12,20",
            "M4.5,12H19.5", "M5.6,8H18.4", "M5.6,16H18.4",
        )
    }
    val NavBar by lazy {
        icon(
            "NavBar",
            "M5,5H19", "M5,9H14",
            "M7,14H17A3,3 0 0 1 17,20H7A3,3 0 0 1 7,14Z",
            "M9.5,17H9.6", "M12,17H12.1", "M14.5,17H14.6",
        )
    }
    val Check by lazy { icon("Check", "M5,12.5L10,17.5L19,7") }
}
