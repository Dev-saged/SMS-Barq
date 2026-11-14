package dev.saged.barq.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.currentBackStackEntryAsState
import dev.saged.barq.R
import dev.saged.barq.ui.components.LocalBottomBarInset
import dev.saged.barq.ui.components.LocalTabReselect
import dev.saged.barq.ui.components.PillNav
import dev.saged.barq.ui.components.PillNavHeight
import dev.saged.barq.ui.components.PillNavItem
import dev.saged.barq.ui.icons.BIcons
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.saged.barq.util.CrashLog
import dev.saged.barq.ui.components.LocalNavVisibilityScope
import dev.saged.barq.ui.settings.CrashLogScreen
import dev.saged.barq.ui.settings.GroupScreen
import dev.saged.barq.ui.settings.GroupsScreen
import dev.saged.barq.ui.settings.GroupPickerScreen
import dev.saged.barq.ui.settings.StatsScreen
import dev.saged.barq.ui.settings.HealthScreen
import dev.saged.barq.ui.settings.DeveloperScreen
import dev.saged.barq.ui.starred.StarredScreen
import dev.saged.barq.ui.settings.SendLimitScreen
import dev.saged.barq.ui.batch.BatchScreen
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.NavType
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.saged.barq.ui.compose.ComposeScreen
import dev.saged.barq.ui.contact.ContactScreen
import dev.saged.barq.ui.inbox.FolderScreen
import dev.saged.barq.ui.failed.FailedScreen
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
    const val THREAD = "thread/{threadId}?focus={focus}"
    const val COMPOSE = "compose?to={to}&body={body}"
    const val ARCHIVE = "folder/archive"
    const val BIN = "folder/bin"
    const val SETTINGS = "settings"
    const val BLOCKED = "blocked"
    const val RULES = "rules"
    const val TEMPLATES = "templates"
    const val AUTO_REPLY = "autoreply"
    const val CONTACT = "contact/{threadId}"
    const val CRASHES = "crashes"
    const val STATS = "stats"
    const val GROUPS = "groups"
    const val GROUP = "group/{groupId}"
    const val GROUP_PICK = "group/{groupId}/pick"
    const val BATCH = "batch/{batchId}?since={since}"
    const val SEND_LIMIT = "sendlimit"
    const val HEALTH = "health"
    const val DEVELOPER = "developer"
    const val STARRED = "starred"
    const val FAILED = "failed"

    fun thread(id: Long) = "thread/$id"
    fun threadAt(id: Long, messageId: Long) = "thread/$id?focus=$messageId"
    fun group(id: Long) = "group/$id"
    fun groupPick(id: Long) = "group/$id/pick"
    fun contact(id: Long) = "contact/$id"
    fun batch(id: Long) = "batch/$id"
    fun external(since: Long) = "batch/0?since=$since"
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
                // The inbox is the first screen: go back to it instead of opening a second one.
                if (route == Routes.INBOX) nav.popBackStack(Routes.INBOX, inclusive = false)
                else {
                    // محادثة مفتوحة بمعرّف آخر تُستبدل، وإلا أعاد singleTop عرض المفتوحة
                    if (route.startsWith("thread/")) nav.popBackStack(Routes.THREAD, inclusive = true)
                    nav.navigate(route) { launchSingleTop = true }
                }
                pendingRoute.value = null
            }
        }
    }

    val back: () -> Unit = { nav.popBackStack() }

    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val pillOn = mainViewModel.settings.collectAsStateWithLifecycle().value?.pillNav ?: true
    val tabInset = if (pillOn) PillNavHeight else 0.dp
    val reselect = remember { MutableSharedFlow<String>(extraBufferCapacity = 1) }
    fun goTab(target: String) {
        when {
            target == route -> reselect.tryEmit(target)
            target == Routes.INBOX -> nav.popBackStack(Routes.INBOX, inclusive = false)
            else -> nav.navigate(target) { popUpTo(Routes.INBOX); launchSingleTop = true }
        }
    }

    // The crash log records which screen was open.
    LaunchedEffect(nav) {
        nav.currentBackStackEntryFlow.collect { CrashLog.currentScreen = it.destination.route ?: "-" }
    }

    // The next screen slides in from the reading side's end: from the left in Arabic, the right in English.
    val sign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    NavHost(
        navController = nav,
        startDestination = if (startOnboarding) Routes.ONBOARDING else Routes.INBOX,
        enterTransition = {
            if (tabSwap()) tabFadeIn() else slideInHorizontally(tween(260)) { sign * it / 4 } + fadeIn(tween(220))
        },
        exitTransition = { if (tabSwap()) fadeOut(tween(120)) else fadeOut(tween(180)) },
        popEnterTransition = { if (tabSwap()) tabFadeIn() else fadeIn(tween(220)) },
        popExitTransition = {
            if (tabSwap()) fadeOut(tween(120)) else slideOutHorizontally(tween(240)) { sign * it / 4 } + fadeOut(tween(200))
        },
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(mainViewModel) {
                mainViewModel.finishOnboarding()
                nav.navigate(Routes.INBOX) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            }
        }
        composable(Routes.INBOX) {
            CompositionLocalProvider(
                LocalNavVisibilityScope provides this,
                LocalTabReselect provides reselect,
                LocalBottomBarInset provides tabInset,
            ) {
            InboxScreen(
                mainViewModel = mainViewModel,
                openThread = { nav.navigate(Routes.thread(it)) },
                newMessage = { nav.navigate(Routes.compose()) },
                openArchive = { nav.navigate(Routes.ARCHIVE) },
                openBin = { nav.navigate(Routes.BIN) },
                openStarred = { nav.navigate(Routes.STARRED) },
                openFailed = { nav.navigate(Routes.FAILED) },
                openSettings = { nav.navigate(Routes.SETTINGS) },
                openContact = { nav.navigate(Routes.contact(it)) },
                openThreadAt = { thread, message -> nav.navigate(Routes.threadAt(thread, message)) },
                openCrashLog = { nav.navigate(Routes.CRASHES) },
                openBatch = { nav.navigate(Routes.batch(it)) },
                openExternal = { nav.navigate(Routes.external(it)) },
                openSendLimit = { nav.navigate(Routes.SEND_LIMIT) },
            )
            }
        }
        composable(
            Routes.THREAD,
            arguments = listOf(
                navArgument("threadId") { type = NavType.LongType },
                navArgument("focus") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) {
            CompositionLocalProvider(LocalNavVisibilityScope provides this) {
            ThreadScreen(
                onBack = back,
                forward = { body -> nav.navigate(Routes.compose("", body)) },
                openContact = { nav.navigate(Routes.contact(it)) { launchSingleTop = true } },
            )
            }
        }
        composable(
            Routes.CONTACT,
            arguments = listOf(navArgument("threadId") { type = NavType.LongType }),
        ) {
            ContactScreen(
                onBack = back,
                openThread = { id ->
                    // Coming from the thread → just go back; from the inbox → open the thread.
                    if (!nav.popBackStack(Routes.THREAD, false)) nav.navigate(Routes.thread(id))
                },
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
            TabContent(tabInset) { FolderScreen(bin = false, onBack = back, openThread = { nav.navigate(Routes.thread(it)) }) }
        }
        composable(Routes.BIN) {
            FolderScreen(bin = true, onBack = back, openThread = { nav.navigate(Routes.thread(it)) })
        }
        composable(Routes.SETTINGS) {
            TabContent(tabInset) {
                SettingsScreen(
                    onBack = back,
                    open = { nav.navigate(it) },
                )
            }
        }
        composable(Routes.BLOCKED) { BlockedScreen(onBack = back) }
        composable(Routes.RULES) { RulesScreen(onBack = back) }
        composable(Routes.TEMPLATES) { TemplatesScreen(onBack = back) }
        composable(Routes.AUTO_REPLY) { AutoReplyScreen(onBack = back) }
        composable(Routes.CRASHES) { CrashLogScreen(onBack = back) }
        composable(Routes.STATS) { StatsScreen(onBack = back, openThread = { nav.navigate(Routes.thread(it)) }) }
        composable(Routes.GROUPS) { GroupsScreen(onBack = back, openGroup = { nav.navigate(Routes.group(it)) }) }
        composable(
            Routes.GROUP,
            arguments = listOf(navArgument("groupId") { type = NavType.LongType }),
        ) {
            GroupScreen(onBack = back, pickContacts = { nav.navigate(Routes.groupPick(it)) })
        }
        composable(
            Routes.GROUP_PICK,
            arguments = listOf(navArgument("groupId") { type = NavType.LongType }),
        ) {
            GroupPickerScreen(onBack = back)
        }
        composable(
            Routes.BATCH,
            arguments = listOf(
                navArgument("batchId") { type = NavType.LongType },
                navArgument("since") { type = NavType.LongType; defaultValue = 0L },
            ),
        ) {
            BatchScreen(onBack = back, openThread = { nav.navigate(Routes.thread(it)) })
        }
        composable(Routes.SEND_LIMIT) { SendLimitScreen(onBack = back) }
        composable(Routes.STARRED) {
            TabContent(tabInset) {
                StarredScreen(onBack = back, openAt = { thread, message -> nav.navigate(Routes.threadAt(thread, message)) })
            }
        }
        composable(Routes.FAILED) {
            FailedScreen(onBack = back, openThread = { nav.navigate(Routes.thread(it)) })
        }
        composable(Routes.DEVELOPER) { DeveloperScreen(onBack = back, open = { nav.navigate(it) }) }
        composable(Routes.HEALTH) { HealthScreen(onBack = back, open = { nav.navigate(it) }) }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = pillOn && route in TAB_ROUTES,
            enter = slideInVertically(tween(260)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(160)),
        ) {
            PillNav(
                listOf(
                    PillNavItem(BIcons.Messages, stringResource(R.string.inbox_title), route == Routes.INBOX) { goTab(Routes.INBOX) },
                    PillNavItem(BIcons.Star, stringResource(R.string.starred_title), route == Routes.STARRED) { goTab(Routes.STARRED) },
                    PillNavItem(BIcons.Archive, stringResource(R.string.archive), route == Routes.ARCHIVE) { goTab(Routes.ARCHIVE) },
                    PillNavItem(BIcons.Settings, stringResource(R.string.settings), route == Routes.SETTINGS) { goTab(Routes.SETTINGS) },
                ),
            )
        }
    }
}

private val TAB_ROUTES = setOf(Routes.INBOX, Routes.STARRED, Routes.ARCHIVE, Routes.SETTINGS)

/** Moving between two tabs cross-fades instead of sliding, so the bar stays still. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabSwap() =
    initialState.destination.route in TAB_ROUTES && targetState.destination.route in TAB_ROUTES

private fun tabFadeIn() = fadeIn(tween(200, delayMillis = 60)) + scaleIn(tween(200, delayMillis = 60), initialScale = 0.97f)

@Composable
private fun TabContent(inset: Dp, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalBottomBarInset provides inset, content = content)
