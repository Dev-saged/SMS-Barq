package dev.saged.barq.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.saged.barq.R
import dev.saged.barq.di.barq
import dev.saged.barq.notifications.NotificationActionReceiver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home-screen widget showing the latest verification code. Tap to copy. */
class OtpWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val latest = runCatching { context.barq.messageDao().latestOtp() }.getOrNull()
        val code = latest?.otp
        val name = latest?.address.orEmpty()
        val time = latest?.date?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) }.orEmpty()
        provideContent {
            GlanceTheme {
                Content(context, code, name, time)
            }
        }
    }

    @Composable
    private fun Content(context: Context, code: String?, sender: String, time: String) {
        val base = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.primaryContainer)
            .cornerRadius(24.dp)
            .padding(14.dp)
        val modifier = if (code != null) {
            base.clickable(actionRunCallback<CopyOtpAction>(actionParametersOf(CopyOtpAction.CODE to code)))
        } else base
        Column(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    provider = ImageProvider(R.drawable.ic_shield),
                    contentDescription = null,
                    modifier = GlanceModifier.size(16.dp),
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = if (code != null) "$sender · $time" else context.getString(R.string.widget_no_code),
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 12.sp),
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = code ?: "— — —",
                style = TextStyle(
                    color = GlanceTheme.colors.onPrimaryContainer,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            if (code != null) {
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    text = context.getString(R.string.widget_tap_to_copy),
                    style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                )
            }
        }
    }

    companion object {
        suspend fun refresh(context: Context) = OtpWidget().updateAll(context)
    }
}

class CopyOtpAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val code = parameters[CODE] ?: return
        NotificationActionReceiver.copyToClipboard(context, code)
    }

    companion object {
        val CODE = ActionParameters.Key<String>("code")
    }
}

class OtpWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = OtpWidget()
}
