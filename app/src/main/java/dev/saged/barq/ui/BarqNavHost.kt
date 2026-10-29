package dev.saged.barq.ui

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.saged.barq.ui.compose.ComposeScreen
import dev.saged.barq.ui.inbox.FolderScreen
import dev.saged.barq.ui.inbox.InboxScreen
import dev.saged.barq.ui.onboarding.OnboardingScreen
import dev.saged.barq.ui.settings.AutoReplyScreen
import dev.saged.barq.ui.settings.BlockedScreen
import dev.saged.barq.ui.settings.RulesScreen
import dev.saged.barq.ui.settings.SettingsScreen
import dev.saged.barq.ui.settings.TemplatesScreen
import dev.saged.barq.ui.thread.ThreadScreen
import kotlinx.coroutines.flow.MutableStateFlow

object Routes {
    const val ONBOARDING = "onboarding"
    const val INBOX = "inbox"
    const val THREAD = "thread/{threadId}"
    const val COMPOSE = "compose?to={to}&body={body}"
    const val ARCHIVE = "folder/archive"
    const val BIN = "folder/bin"
    const val SETTINGS = "settings"
    const val BLOCKED = "blocked"
    const val RULES = "rules"
    const val TEMPLATES = "templates"
    const val AUTO_REPLY = "autoreply"

    fun thread(id: Long) = "thread/$id"
    fun compose(to: String = "", body: String = "") = "compose?to=${Uri.encode(to)}&body=${Uri.encode(body)}"
}

@Composable
fun BarqNavHost(
    startOnboarding: Boolean,
    pendingRoute: MutableStateFlow<String?>,
    mainViewModel: MainViewModel,
) {
    val nav = rememberNavController()

    LaunchedEffect(pendingRoute) {
        pendingRoute.collect { route ->
            if (route != null) {
                nav.navigate(route) { launchSingleTop = true }
                pendingRoute.value = null
            }
        }
    }

    val back: () -> Unit = { nav.popBackStack() }

    NavHost(
        navController = nav,
        startDestination = if (startOnboarding) Routes.ONBOARDING else Routes.INBOX,
        // In RTL the next screen slides in from the left.
        enterTransition = { slideInHorizontally(tween(260)) { -it / 4 } + fadeIn(tween(220)) },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { slideOutHorizontally(tween(240)) { -it / 4 } + fadeOut(tween(200)) },
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(mainViewModel) {
                mainViewModel.finishOnboarding()
                nav.navigate(Routes.INBOX) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            }
        }
        composable(Routes.INBOX) {
            InboxScreen(
                mainViewModel = mainViewModel,
                openThread = { nav.navigate(Routes.thread(it)) },
                newMessage = { nav.navigate(Routes.compose()) },
                openArchive = { nav.navigate(Routes.ARCHIVE) },
                openBin = { nav.navigate(Routes.BIN) },
                openSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            Routes.THREAD,
            arguments = listOf(navArgument("threadId") { type = NavType.LongType }),
        ) {
            ThreadScreen(
                onBack = back,
                forward = { body -> nav.navigate(Routes.compose("", body)) },
            )
        }
        composable(
            Routes.COMPOSE,
            arguments = listOf(
                navArgument("to") { type = NavType.StringType; defaultValue = "" },
                navArgument("body") { type = NavType.StringType; defaultValue = "" },
            ),
        ) {
            ComposeScreen(
                onBack = back,
                onSent = { threadId ->
                    nav.navigate(Routes.thread(threadId)) { popUpTo(Routes.INBOX) }
                },
                onSentMany = { nav.popBackStack(Routes.INBOX, false) },
            )
        }
        composable(Routes.ARCHIVE) {
            FolderScreen(bin = false, onBack = back, openThread = { nav.navigate(Routes.thread(it)) })
        }
        composable(Routes.BIN) {
            FolderScreen(bin = true, onBack = back, openThread = { nav.navigate(Routes.thread(it)) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = back,
                open = { nav.navigate(it) },
            )
        }
        composable(Routes.BLOCKED) { BlockedScreen(onBack = back) }
        composable(Routes.RULES) { RulesScreen(onBack = back) }
        composable(Routes.TEMPLATES) { TemplatesScreen(onBack = back) }
        composable(Routes.AUTO_REPLY) { AutoReplyScreen(onBack = back) }
    }
}
