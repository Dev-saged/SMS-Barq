package dev.saged.barq.ui.components

import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** نص بصيغة الجمع الصحيحة للغة الحالية (مفرد/مثنى/جمع في العربية). */
@Composable
fun pluralText(@PluralsRes id: Int, count: Int, vararg args: Any): String {
    LocalConfiguration.current
    return LocalContext.current.resources.getQuantityString(id, count, *args)
}
