package dev.saged.barq.ui.whatsnew

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.saged.barq.BuildConfig
import dev.saged.barq.R
import dev.saged.barq.ui.MainViewModel

private data class Item(val icon: ImageVector, @StringRes val title: Int, @StringRes val text: Int)

/** What this version brings (shown once after updating, and from Settings → What's new). */
private val ITEMS = listOf(
    Item(Icons.Outlined.Fingerprint, R.string.wn_lock_t, R.string.wn_lock_d),
    Item(Icons.Outlined.UploadFile, R.string.wn_excel_t, R.string.wn_excel_d),
    Item(Icons.Outlined.Badge, R.string.wn_name_t, R.string.wn_name_d),
    Item(Icons.Outlined.SimCard, R.string.wn_sim_t, R.string.wn_sim_d),
    Item(Icons.Outlined.Star, R.string.wn_star_t, R.string.wn_star_d),
    Item(Icons.Outlined.Category, R.string.wn_class_t, R.string.wn_class_d),
    Item(Icons.Outlined.EmojiEmotions, R.string.wn_emoji_t, R.string.wn_emoji_d),
    Item(Icons.Outlined.Wifi, R.string.wn_gateway_t, R.string.wn_gateway_d),
)

/**
 * Shows "what's new" once after an update (also from versions before 1.5.0, which never saved
 * a version). A fresh install saves the version when onboarding ends, so it never sees it.
 */
@Composable
fun WhatsNewHost(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val seen = settings?.seenVersion ?: return
    val current = BuildConfig.VERSION_NAME
    when {
        seen == current -> Unit
        // A fix-up release (1.5.0 → 1.5.1) has nothing new to show: just remember it quietly.
        featureVersion(seen) == featureVersion(current) -> LaunchedEffect(current) { vm.markVersionSeen() }
        else -> WhatsNewSheet(onClose = vm::markVersionSeen)
    }
}

/** "1.5.1" → "1.5": the part of the version that changes when there are new features. */
private fun featureVersion(version: String): String = version.split('.').take(2).joinToString(".")

@Composable
fun WhatsNewSheet(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val scheme = MaterialTheme.colorScheme
        Column(Modifier.fillMaxSize().background(scheme.background)) {
            // Hero: brand gradient, the logo and the version.
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 34.dp, bottomEnd = 34.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF4F63E6), Color(0xFF2F3BA8), Color(0xFF121747)), Offset.Zero, Offset.Infinite))
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 26.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val logo = remember { Animatable(0.4f) }
                    LaunchedEffect(Unit) { logo.animateTo(1f, tween(650)) }
                    Image(
                        painterResource(R.drawable.logo_mark), null,
                        modifier = Modifier
                            .size(72.dp)
                            .graphicsLayer { scaleX = logo.value; scaleY = logo.value; rotationZ = (1 - logo.value) * -40f }
                            .clip(RoundedCornerShape(22.dp)),
                    )
                    Column {
                        Text(stringResource(R.string.wn_title), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.version_value, BuildConfig.VERSION_NAME),
                            color = Color(0xFF1B2470),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White)
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(ITEMS) { i, item -> Card(item, delayMs = 120 + i * 70) }
            }
            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = scheme.primary),
            ) { Text(stringResource(R.string.wn_go), fontWeight = FontWeight.Bold, fontSize = 17.sp) }
        }
    }
}

@Composable
private fun Card(item: Item, delayMs: Int) {
    val scheme = MaterialTheme.colorScheme
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { anim.animateTo(1f, tween(450, delayMillis = delayMs)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = anim.value; translationY = (1 - anim.value) * 60f }
            .clip(RoundedCornerShape(22.dp))
            .background(scheme.surfaceContainerLow)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(15.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF6F82FF), Color(0xFF3243B8)))),
            contentAlignment = Alignment.Center,
        ) { Icon(item.icon, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
        Column(Modifier.weight(1f)) {
            Text(stringResource(item.title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(item.text), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
}
