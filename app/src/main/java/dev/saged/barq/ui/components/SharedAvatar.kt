package dev.saged.barq.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Lets a conversation's avatar fly from the inbox row to the thread header; a no-op outside those two screens. */
@Composable
fun Modifier.sharedAvatar(threadId: Long): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalNavVisibilityScope.current ?: return this
    return with(shared) { sharedElement(rememberSharedContentState("avatar_$threadId"), visibility) }
}
